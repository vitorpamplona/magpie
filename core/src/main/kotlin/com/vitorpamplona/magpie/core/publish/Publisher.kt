/*
 * Copyright (c) 2026 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.magpie.core.publish

import com.vitorpamplona.magpie.core.nostr.NostrIO
import com.vitorpamplona.magpie.core.resolve.BlobFetcher
import com.vitorpamplona.magpie.core.resolve.BlobStore
import com.vitorpamplona.magpie.core.resolve.RelayUrls
import com.vitorpamplona.magpie.core.resolve.VouchedKey
import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.core.util.StripedMutex
import com.vitorpamplona.magpie.spec.EventVerification
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.WinnerSelection
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public data class PublishSettings(
    val repoDir: File,
    val group: String,
    val version: String,
    /** Root artifactId; guessed (the shortest) when null. */
    val rootArtifact: String? = null,
    /** Where to look the publisher's relay list (kind 10002) up, besides its domain's relays. */
    val indexRelays: List<String>,
    /** `--partial`: upload this host's blobs and write a partial manifest; sign no release. */
    val partial: Boolean = false,
    val partialDir: File? = null,
    val partialName: String = "partial",
    val dryRun: Boolean = false,
)

public data class PublishResult(
    val coordinate: String,
    val manifestSha256: String,
    val eventId: String?,
    val uploaded: Int,
    val signatures: Int,
    val acceptedBy: List<String>,
    val partialManifest: File? = null,
)

/** The publisher's Blossom servers (kind 10063), NIP-65 write relays, and the relays its domain lists for it. */
public data class PublisherContext(
    val pubKey: HexKey,
    val servers: List<String>,
    val writeRelays: List<String>,
    val domainRelays: List<String> = emptyList(),
) {
    /** Where consumers read this key's events: its write relays and its domain's relays. */
    val homeRelays: List<String> get() = (writeRelays + domainRelays).distinct()
}

/** What the list tasks (server list, relay list) need. */
public data class ListSettings(
    /** The group whose domain may list relays for the key; null skips that lookup. */
    val group: String?,
    val indexRelays: List<String>,
)

public class MissingServerListException(
    npub: String,
) : IllegalStateException(
        "$npub has no Blossom server list (kind 10063), so Magpie doesn't know where to upload. Create one with\n" +
            "    ./gradlew createNostrServerList --server=https://blossom.example.com\n" +
            "(any BUD-02 server; repeat --server for more), then publish again.",
    )

/**
 * Most hashes one upload authorization covers by default: about 5 KB of `Authorization` header,
 * well under nginx's default 8 KB limit, which most Blossom servers sit behind.
 */
public const val MAX_HASHES_PER_AUTH: Int = 48

public class VersionAlreadyReleasedException(
    message: String,
) : IllegalStateException(message)

/**
 * Publishes a local Maven repository as a Magpie release: hash every file, make sure each blob
 * can live on at least one of the publisher's Blossom servers (BUD-06), upload what's missing
 * under BUD-02 authorizations of up to [MAX_HASHES_PER_AUTH] hashes each, upload the manifest,
 * sign the release and send it to the publisher's own relays. Nothing is signed until every
 * check passes.
 */
public class Publisher(
    private val nostr: NostrIO,
    private val blossom: BlossomClient,
    private val signer: MagpieSigner,
    private val http: OkHttpClient,
    private val log: MagpieLog,
    private val clock: Clock = Clock.SYSTEM,
    private val parallelism: Int = 8,
    /** Checks the signer is the key the group's domain vouches for; null skips it. */
    private val keyCheck: ReleaseKeyCheck? = null,
    /**
     * Most hashes one upload authorization covers. The authorization travels in the
     * `Authorization` header, and many Blossom servers sit behind nginx, whose default 8 KB
     * header limit answers `400 Request Header Or Cookie Too Large` past about 78 hashes.
     */
    private val hashesPerAuth: Int = MAX_HASHES_PER_AUTH,
    /** How many HEAD and BUD-06 checks run at once: small requests, so more than uploads. */
    private val checkParallelism: Int = 32,
    /** How many of those go to any one server at once, so a key with one server doesn't flood it. */
    private val checksPerServer: Int = 8,
) {
    init {
        require(hashesPerAuth > 0) { "hashesPerAuth must be positive" }
    }

    private fun now() = clock.nowMillis() / 1000

    /**
     * Signs [template] and checks what came back: the expected key, exactly the template asked
     * for, and a valid signature. A misbehaving bunker can't slip in another key or other tags.
     */
    private suspend fun signChecked(
        template: EventTemplate<*>,
        /** The key the domain vouched for before the signer connected, which the signer must have. */
        vouched: ReleaseKey? = null,
    ): Event {
        // A remote signer connects here, on the first request, right before it is asked to sign.
        val expected = signer.pubKey()
        vouched?.requireSigner(expected)
        val event = signer.sign(template)
        val problem =
            when {
                event.pubKey != expected -> "it is signed by ${PublicKeys.npub(event.pubKey)}, not ${PublicKeys.npub(expected)}"
                event.kind != template.kind -> "kind ${event.kind} instead of ${template.kind}"
                event.createdAt != template.createdAt -> "created_at changed"
                event.content != template.content -> "content changed"
                !event.tags.contentDeepEquals(template.tags) -> "tags changed"
                !EventVerification.isValid(event) -> "its id or signature is invalid"
                else -> null
            }
        if (problem != null) throw IllegalStateException("The signer returned an event that doesn't match the request ($problem). Nothing was published.")
        return event
    }

    /**
     * Reads the signer's relay list (kind 10002) and server list (kind 10063) the way consumers
     * find them: on the index relays and the domain's relays first, then on the write relays the
     * relay list names.
     */
    public suspend fun context(
        indexRelays: List<String>,
        domainRelays: List<String> = emptyList(),
        /** The key to read the lists of, when known without connecting; else the signer's. */
        key: HexKey? = null,
    ): PublisherContext {
        val pubKey = key ?: signer.pubKey()
        val (serverList, relayList) = readLists(pubKey, indexRelays, domainRelays)
        val servers =
            serverList
                ?.tags
                ?.filter { it.size >= 2 && it[0] == "server" }
                ?.map { it[1].trimEnd('/') }
                ?.filter { it.toHttpUrlOrNull() != null }
                ?.distinct()
                .orEmpty()
        if (servers.isEmpty()) throw MissingServerListException(PublicKeys.npub(pubKey))
        return PublisherContext(pubKey, servers, RelayUrls.writeRelays(relayList), RelayUrls.clean(domainRelays))
    }

    /** The newest server list and relay list of [pubKey], or null for the ones no relay has. */
    private suspend fun readLists(
        pubKey: HexKey,
        indexRelays: List<String>,
        domainRelays: List<String>,
    ): Pair<Event?, Event?> {
        val first = (RelayUrls.clean(indexRelays, max = Int.MAX_VALUE) + RelayUrls.clean(domainRelays)).distinct()
        if (first.isEmpty()) {
            throw IllegalStateException(
                "No relays to read your relay and server lists from: set magpie { indexRelays(\"wss://…\") } (or -Pmagpie.indexRelays=…), " +
                    "or list relays for your key in your domain's nostr.json. Nothing was signed.",
            )
        }
        val filter = listOf(Filter(kinds = listOf(MagpieSpec.KIND_BLOSSOM_SERVERS, MagpieSpec.KIND_RELAY_LIST), authors = listOf(pubKey)))
        val result = nostr.query(first, filter)
        if (!result.anyAnswered) {
            throw IllegalStateException("None of the relays (${first.joinToString()}) answered, so your server and relay lists can't be read. Nothing was signed.")
        }

        fun newest(
            events: List<Event>,
            kind: Int,
        ) = events.filter { it.kind == kind && it.pubKey == pubKey }.maxByOrNull { it.createdAt }
        var events = result.events
        val unasked = RelayUrls.writeRelays(newest(events, MagpieSpec.KIND_RELAY_LIST)).filter { it !in first }
        if (unasked.isNotEmpty()) events = events + nostr.query(unasked, filter).events
        return newest(events, MagpieSpec.KIND_BLOSSOM_SERVERS) to newest(events, MagpieSpec.KIND_RELAY_LIST)
    }

    /**
     * Where an event of this key goes: its own relays (write relays and its domain's), which is
     * where consumers read it. A key with no relays of its own is looked for on consumers' index relays,
     * so its events go there instead, with a warning that names the fix.
     */
    private fun targets(
        pubKey: HexKey,
        homeRelays: List<String>,
        indexRelays: List<String>,
    ): List<String> {
        val home =
            homeRelays.ifEmpty {
                log.warn(
                    "Magpie: ${PublicKeys.npub(pubKey)} has no relay list (kind 10002) and its domain's nostr.json lists no relays for it, so " +
                        "consumers look for its events on their index relays; publishing to ${indexRelays.joinToString()}. Publish a relay list " +
                        "with createNostrRelayList --relay=wss://… so your events live on relays you choose.",
                )
                RelayUrls.clean(indexRelays, max = Int.MAX_VALUE)
            }
        if (home.isEmpty()) throw IllegalStateException("No relays to publish to: set indexRelays. Nothing was signed.")
        return home.distinct()
    }

    /** The relays [group]'s domain lists for the signer; empty when there is no check, or it fails (with a warning). */
    private suspend fun domainRelaysLenient(group: String?): List<String> {
        val check = keyCheck ?: return emptyList()
        if (group.isNullOrBlank()) return emptyList()
        return try {
            check.verify(group, signer.pubKey())
        } catch (e: Exception) {
            log.warn("Magpie: ${e.message} Continuing without your domain's relays.")
            emptyList()
        }
    }

    /**
     * The key a release is checked and signed with, and its domain's relays. [vouched] is set
     * when the domain named the key before the signer was reached: [signChecked] then makes sure
     * the signer has it before sending anything to sign.
     */
    private class ReleaseKey(
        val pubKey: HexKey,
        val domainRelays: List<String>,
        val vouched: VouchedKey.Found? = null,
        val group: String = "",
    ) {
        fun requireSigner(actual: HexKey) {
            if (vouched != null && vouched.pubKey != actual) throw ReleaseKeyCheck.mismatch(vouched, group, actual)
        }
    }

    /**
     * The release key and its domain's relays. With a key check, the domain names the key, so a
     * remote signer is only connected when the release is ready to sign, next to its signature
     * requests; without one, the signer is asked now. A check-only run connects anyway, to
     * prove the signer is the release key.
     */
    private suspend fun releaseKey(
        group: String,
        dryRun: Boolean,
    ): ReleaseKey {
        val check = keyCheck ?: return ReleaseKey(signer.pubKey(), emptyList())
        signer.pubKeyIfKnown()?.let { known -> return ReleaseKey(known, check.verify(group, known)) }
        // Only when no domain lists a key: reaching the signer then fills its key into the error.
        val found = check.vouched(group) { signerKeyOrNull() }
        val key = ReleaseKey(found.pubKey, found.relays, found, group)
        if (dryRun) {
            key.requireSigner(signer.pubKey())
        } else {
            log.lifecycle("Magpie: ${found.identifier} names ${PublicKeys.npub(found.pubKey)} as the release key; the signer is asked once the release is ready to sign")
        }
        return key
    }

    /** Runs [block] holding one of [server]'s own check permits, then one of the shared [gate]'s. */
    private suspend inline fun <T> checkSlot(
        gate: Semaphore,
        perServer: Map<String, Semaphore>,
        server: String,
        block: () -> T,
    ): T = perServer.getValue(server).withPermit { gate.withPermit(block) }

    private suspend fun signerKeyOrNull(): HexKey? =
        try {
            signer.pubKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    public suspend fun publish(settings: PublishSettings): PublishResult {
        val root = settings.rootArtifact ?: RepoScanner.guessRootArtifact(settings.repoDir, settings.group, settings.version)
        val scanned = RepoScanner.scan(settings.repoDir, settings.group, settings.version, root, log)
        val manifest = scanned.manifest
        log.lifecycle("Magpie: ${manifest.coordinate} has ${manifest.files.size} files in ${manifest.artifacts.size} modules")

        val key = releaseKey(settings.group, settings.dryRun)
        val context = context(settings.indexRelays, key.domainRelays, key.pubKey)
        val relays = targets(context.pubKey, context.homeRelays, settings.indexRelays)
        if (!manifest.coordinate.isSnapshot) refuseOverwrite(manifest, context, relays)

        val files =
            scanned.files.map { (key, file) -> BlobSource(manifest[key]!!.sha256, file.length(), contentTypeOf(key), file = file) }

        if (settings.partial) {
            val hosted = host(files, context.servers, settings.dryRun, key)
            if (settings.dryRun) {
                log.lifecycle("Magpie: dry run; this host's ${files.size} files would upload, no partial manifest written")
                return PublishResult(manifest.coordinate.toString(), manifest.sha256Hex(), null, 0, signer.signatures, emptyList())
            }
            val dir = settings.partialDir ?: throw IllegalArgumentException("--partial needs a partial manifest directory")
            val out = File(dir, "${settings.partialName}.json")
            Files2.writeAtomically(out, manifest.toBytes())
            log.lifecycle("Magpie: uploaded ${hosted.uploaded} blobs; wrote partial manifest $out. Run closeNostrRelease once every host is done.")
            return PublishResult(manifest.coordinate.toString(), manifest.sha256Hex(), null, hosted.uploaded, signer.signatures, emptyList(), out)
        }

        val missing = ModuleChecker.missingReferences(manifest) { key -> scanned.files.getValue(key).readBytes() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Refusing to publish ${manifest.coordinate}: Gradle module metadata references files that were not published:\n  " + missing.joinToString("\n  "))
        }
        return finish(manifest, files, context, relays, settings.dryRun, key)
    }

    /** Merges the partial manifests in [PublishSettings.partialDir] and publishes the release. */
    public suspend fun close(settings: PublishSettings): PublishResult {
        val dir = settings.partialDir ?: throw IllegalArgumentException("No partial manifest directory")
        val partials = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        require(partials.isNotEmpty()) { "No partial manifests in $dir. Copy every host's build/magpie/partials/*.json there first." }
        val manifest = Manifest.merge(partials.map { Manifest.parse(it.readText()) })
        require(manifest.coordinate.version == settings.version && manifest.coordinate.group == settings.group) {
            "The partial manifests are for ${manifest.coordinate}, not ${settings.group}:…:${settings.version}"
        }
        settings.rootArtifact?.let { root ->
            require(manifest.coordinate.artifact == root) { "The partial manifests' root module is ${manifest.coordinate.artifact}, but this build publishes $root" }
        }
        log.lifecycle("Magpie: merged ${partials.size} partial manifests into ${manifest.files.size} files")

        val key = releaseKey(settings.group, settings.dryRun)
        val context = context(settings.indexRelays, key.domainRelays, key.pubKey)
        val relays = targets(context.pubKey, context.homeRelays, settings.indexRelays)
        if (!manifest.coordinate.isSnapshot) refuseOverwrite(manifest, context, relays)

        // Every blob must already be on a server, at the size the manifest says.
        val present = ConcurrentHashMap<String, MutableSet<String>>()
        val wrongSize = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            val gate = Semaphore(checkParallelism)
            val perServer = context.servers.associateWith { Semaphore(checksPerServer) }
            manifest.files
                .flatMap { (key, file) ->
                    context.servers.map { server ->
                        async {
                            checkSlot(gate, perServer, server) {
                                val size = blossom.size(server, file.sha256) ?: return@checkSlot
                                if (size >= 0 && size != file.size) {
                                    wrongSize += "$key: $server has $size bytes, the partial manifest says ${file.size}"
                                } else {
                                    present.computeIfAbsent(file.sha256) { ConcurrentHashMap.newKeySet() } += server
                                }
                            }
                        }
                    }
                }.awaitAll()
        }
        if (wrongSize.isNotEmpty()) throw IllegalStateException("Refusing to sign: sizes disagree with the partial manifests:\n  " + wrongSize.sorted().joinToString("\n  "))
        val absent = manifest.files.filter { (_, file) -> present[file.sha256].isNullOrEmpty() }.keys
        if (absent.isNotEmpty()) {
            throw IllegalStateException("Refusing to sign: these files are on none of ${context.servers}:\n  " + absent.sorted().joinToString("\n  "))
        }

        // Every .module reference must be in the merged set.
        val fetcher = BlobFetcher(http, BlobStore(File(dir, ".blobs")), log)
        val missing =
            ModuleChecker.missingReferences(manifest) { key ->
                val entry = manifest[key]!!
                fetcher.fetch(entry.sha256, context.servers, entry.size).readBytes()
            }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Refusing to sign ${manifest.coordinate}: Gradle module metadata references files no host published (did a CI host fail?):\n  " +
                    missing.joinToString("\n  "),
            )
        }
        return finish(manifest, emptyList(), context, relays, settings.dryRun, key, known = present)
    }

    private suspend fun finish(
        manifest: Manifest,
        files: List<BlobSource>,
        context: PublisherContext,
        relays: List<String>,
        dryRun: Boolean,
        key: ReleaseKey,
        known: Map<String, Set<String>> = emptyMap(),
    ): PublishResult {
        val manifestBytes = manifest.toBytes()
        val manifestBlob = BlobSource(manifest.sha256Hex(), manifestBytes.size.toLong(), MagpieSpec.MANIFEST_CONTENT_TYPE, bytes = manifestBytes)
        val allBlobs = manifest.files.values.map { it.sha256 } + manifestBlob.sha256

        // Hints: the servers that hold every file of the release, else every server that holds any.
        fun hintsFor(presence: Map<String, Set<String>>): List<String> {
            val holders = allBlobs.associateWith { sha -> presence[sha].orEmpty() + known[sha].orEmpty() }
            val complete = context.servers.filter { s -> allBlobs.all { s in holders.getValue(it) } }
            return complete.ifEmpty { context.servers.filter { s -> holders.values.any { s in it } } }
        }

        fun template(hints: List<String>): EventTemplate<*> =
            if (manifest.coordinate.isSnapshot) {
                MagpieEvents.snapshot(manifest, manifestBlob.sha256, manifestBlob.size, servers = hints, createdAt = now())
            } else {
                MagpieEvents.release(manifest, manifestBlob.sha256, manifestBlob.size, servers = hints, createdAt = now())
            }

        // The release is signed with the upload authorizations, before any upload, with the hints
        // the checks predict, so a person approving on a remote signer sees every request together.
        // It is only published once every blob is up.
        var early: Pair<List<String>, Event>? = null
        val hosted =
            host(files + manifestBlob, context.servers, dryRun, key, known) { predicted ->
                val hints = hintsFor(predicted)
                early = hints to signChecked(template(hints), key)
            }
        if (dryRun) {
            log.lifecycle("Magpie: dry run; ${manifest.coordinate} checks passed, nothing uploaded or signed")
            return PublishResult(manifest.coordinate.toString(), manifestBlob.sha256, null, 0, signer.signatures, emptyList())
        }

        val hints = hintsFor(hosted.present)
        val signedEarly = early
        var discarded = 0
        val event =
            when {
                signedEarly == null -> signChecked(template(hints), key)
                signedEarly.first == hints -> signedEarly.second
                else -> {
                    // A server the checks counted on failed its uploads, so the early release
                    // names a server that lacks some files. Try to sign it with the right hints;
                    // if that isn't approved, the early one still works: hints are only where
                    // clients look first, and every blob is on a server of the key's list.
                    log.warn("Magpie: the release's server hints changed during upload (${signedEarly.first} -> $hints); signing it again")
                    try {
                        signChecked(template(hints), key).also { discarded = 1 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Magpie: the release with corrected hints was not signed (${e.message}); publishing the one signed before uploading")
                        signedEarly.second
                    }
                }
            }
        val results = nostr.publish(relays, event)
        val accepted = results.filterValues { it }.keys.toList()
        if (accepted.isEmpty()) {
            throw IllegalStateException(
                "No relay accepted ${manifest.coordinate} (${results.keys.joinToString()}). The signed event, to publish by hand:\n${event.toJson()}",
            )
        }
        log.lifecycle(
            "Magpie: published ${manifest.coordinate} as ${event.id} to ${accepted.size}/${results.size} relays " +
                "(${hosted.uploaded} blobs uploaded, ${signer.signatures} signatures" +
                (if (discarded > 0) ", one a release replaced for its stale server hints)" else ")"),
        )
        return PublishResult(manifest.coordinate.toString(), manifestBlob.sha256, event.id, hosted.uploaded, signer.signatures, accepted)
    }

    /**
     * Fails if any module of [manifest] already has a release at this version by this key, and
     * also if that can't be known: an earlier release went to the publisher's own relays (write
     * relays and its domain's), so at least one of them must answer (any relay, when there are
     * none). Silence is not "no".
     */
    private suspend fun refuseOverwrite(
        manifest: Manifest,
        context: PublisherContext,
        relays: List<String>,
    ) {
        val modules = manifest.modules()
        val result =
            nostr.query(
                relays,
                listOf(
                    Filter(
                        kinds = listOf(MagpieSpec.KIND_RELEASE),
                        authors = listOf(context.pubKey),
                        tags = mapOf(MagpieSpec.TAG_MODULE to modules.map { it.key }, MagpieSpec.TAG_VERSION to listOf(manifest.coordinate.version)),
                    ),
                ),
            )
        val homeRelays = context.homeRelays.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }.toSet()
        val confirmed = if (homeRelays.isEmpty()) result.anyAnswered else result.answered.any { it in homeRelays }
        if (!confirmed) {
            throw IllegalStateException(
                "Can't confirm ${manifest.coordinate} isn't already released: " +
                    (if (homeRelays.isEmpty()) "no relay" else "none of your relays (${homeRelays.joinToString()})") +
                    " answered. Nothing was signed; try again when they're reachable.",
            )
        }
        val clash =
            modules.firstNotNullOfOrNull { module ->
                WinnerSelection.releasesFor(result.events, module, manifest.coordinate.version, context.pubKey).firstOrNull()?.let { module to it }
            }
        if (clash != null) {
            throw VersionAlreadyReleasedException(
                "${clash.first}:${manifest.coordinate.version} is already released (event ${clash.second.id}). " +
                    "Releases are immutable: publish a new version.",
            )
        }
    }

    private class Hosted(
        val uploaded: Int,
        /** sha256 -> servers that now hold it. */
        val present: Map<String, Set<String>>,
    )

    /**
     * Makes every blob present on at least one server. Checks first (HEAD and BUD-06 preflight),
     * so a blob no server will take fails the publish before anything is signed; then signs one
     * authorization per [hashesPerAuth] hashes and uploads in parallel. A server that refuses them
     * gets one authorization per blob (each signed once, for every such server); a server that
     * refuses that too is given up on rather than costing a signature per blob.
     */
    private suspend fun host(
        blobs: List<BlobSource>,
        servers: List<String>,
        dryRun: Boolean,
        key: ReleaseKey,
        known: Map<String, Set<String>> = emptyMap(),
        /**
         * Runs beside the upload authorizations' signing and the uploads, started before any
         * upload, with the servers expected to hold each blob afterwards (those that have it, and
         * those whose checks said they would take it). [host] returns once it is done too. Not
         * called when nothing needs uploading.
         */
        beforeUpload: (suspend (predicted: Map<String, Set<String>>) -> Unit)? = null,
    ): Hosted {
        val unique = blobs.distinctBy { it.sha256 }
        val present = ConcurrentHashMap<String, MutableSet<String>>()
        unique.forEach { present[it.sha256] = ConcurrentHashMap.newKeySet<String>().apply { addAll(known[it.sha256].orEmpty()) } }
        val lacking = ConcurrentHashMap<String, MutableList<BlobSource>>()
        val refusals = ConcurrentHashMap<String, MutableList<String>>()
        val willTake = ConcurrentHashMap<String, MutableSet<String>>()
        coroutineScope {
            val gate = Semaphore(checkParallelism)
            val perServer = servers.associateWith { Semaphore(checksPerServer) }
            servers
                .flatMap { server ->
                    unique.filter { server !in present.getValue(it.sha256) }.map { blob ->
                        async {
                            checkSlot(gate, perServer, server) {
                                if (blossom.has(server, blob.sha256)) {
                                    present.getValue(blob.sha256) += server
                                } else {
                                    when (val preflight = blossom.preflight(server, blob)) {
                                        is Preflight.Refused -> {
                                            refusals.computeIfAbsent(blob.sha256) { java.util.Collections.synchronizedList(mutableListOf()) } +=
                                                "$server: ${preflight.status} ${preflight.reason}"
                                        }

                                        else -> {
                                            willTake.computeIfAbsent(blob.sha256) { ConcurrentHashMap.newKeySet() } += server
                                            lacking.computeIfAbsent(server) { java.util.Collections.synchronizedList(mutableListOf()) } += blob
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
        }
        val homeless = unique.filter { present.getValue(it.sha256).isEmpty() && willTake[it.sha256].isNullOrEmpty() }
        if (homeless.isNotEmpty()) {
            throw IllegalStateException(
                "No Blossom server can host these blobs, so nothing was signed:\n  " +
                    homeless.joinToString("\n  ") { "${it.sha256} (${it.size} bytes): ${refusals[it.sha256].orEmpty().joinToString("; ")}" },
            )
        }
        if (dryRun || lacking.isEmpty()) return Hosted(0, present)

        val toUpload = lacking.values.flatten().distinctBy { it.sha256 }
        val predicted = unique.associate { it.sha256 to (present.getValue(it.sha256) + willTake[it.sha256].orEmpty()) }
        val sharedRefused = ConcurrentHashMap.newKeySet<String>()
        val givenUp = ConcurrentHashMap.newKeySet<String>()
        val perBlobWorks = ConcurrentHashMap.newKeySet<String>()
        val probes = StripedMutex()
        val perBlob = ConcurrentHashMap<String, Event>()
        val perBlobLock = StripedMutex()
        val uploaded = AtomicInteger()

        suspend fun perBlobAuth(blob: BlobSource): Event =
            perBlob[blob.sha256] ?: perBlobLock.forKey(blob.sha256).withLock {
                perBlob[blob.sha256] ?: signChecked(BlossomClient.uploadAuth(listOf(blob.sha256), lacking.keys, now()), key).also { perBlob[blob.sha256] = it }
            }

        coroutineScope {
            // Every signature request goes out at once: a remote signer queues them, so its user
            // approves them in a row, and the NIP-46 client matches each answer to its request by
            // id. One authorization per batch of hashes keeps its header under common size limits;
            // a batch's uploads start as soon as its authorization is back. The release is signed
            // beside them and awaited only once the uploads are done.
            val release = beforeUpload?.let { sign -> async { sign(predicted) } }
            val sharedBy = HashMap<String, Deferred<Event>>()
            val batchSize = HashMap<String, Int>()
            toUpload.chunked(hashesPerAuth).forEach { batch ->
                val auth = async { signChecked(BlossomClient.uploadAuth(batch.map { it.sha256 }, lacking.keys, now()), key) }
                batch.forEach {
                    sharedBy[it.sha256] = auth
                    batchSize[it.sha256] = batch.size
                }
            }
            val gate = Semaphore(parallelism)
            lacking
                .flatMap { (server, list) -> list.map { server to it } }
                .map { (server, blob) ->
                    async {
                        val shared = sharedBy.getValue(blob.sha256).await()
                        gate.withPermit {
                            if (server in givenUp) return@withPermit
                            var outcome = if (server in sharedRefused) null else blossom.upload(server, blob, shared)
                            if (outcome == null || (!outcome.ok && outcome.isAuthRefusal && batchSize.getValue(blob.sha256) > 1)) {
                                if (sharedRefused.add(server) && outcome != null) {
                                    log.info("$server refused the shared upload authorization (${outcome.reason}); using one per blob")
                                }
                                outcome =
                                    if (server in perBlobWorks) {
                                        blossom.upload(server, blob, perBlobAuth(blob))
                                    } else {
                                        // The first per-blob attempt on a server is a probe; the others wait for its
                                        // verdict, so a server that refuses everything costs one signature, not one per blob.
                                        probes.forKey(server).withLock {
                                            if (server in givenUp) return@withPermit
                                            blossom.upload(server, blob, perBlobAuth(blob)).also { probe ->
                                                when {
                                                    probe.ok -> perBlobWorks += server
                                                    probe.isAuthRefusal && givenUp.add(server) -> log.warn("$server refuses uploads from this key (${probe.status} ${probe.reason}); skipping it")
                                                }
                                            }
                                        }
                                    }
                            }
                            if (outcome.ok) {
                                uploaded.incrementAndGet()
                                present.getValue(blob.sha256) += server
                            } else if (server !in givenUp) {
                                log.warn("Upload of ${blob.sha256} to $server failed: ${outcome.status} ${outcome.reason}")
                            }
                        }
                    }
                }.awaitAll()
            release?.await()
        }
        val stillMissing = unique.filter { present.getValue(it.sha256).isEmpty() }
        if (stillMissing.isNotEmpty()) {
            throw IllegalStateException("These blobs could not be uploaded to any server, so the release was not signed:\n  " + stillMissing.joinToString("\n  ") { it.sha256 })
        }
        return Hosted(uploaded.get(), present)
    }

    /** Publishes a kind 10063 Blossom server list to the signer's own relays. */
    public suspend fun createServerList(
        servers: List<String>,
        settings: ListSettings,
    ): Event {
        require(servers.isNotEmpty()) { "Give at least one Blossom server" }
        val pubKey = signer.pubKey()
        val domainRelays = RelayUrls.clean(domainRelaysLenient(settings.group))
        val (_, relayList) = readLists(pubKey, settings.indexRelays, domainRelays)
        val relays = targets(pubKey, (RelayUrls.writeRelays(relayList) + domainRelays).distinct(), settings.indexRelays)
        val tags = servers.map { arrayOf("server", it.trimEnd('/')) }.toTypedArray()
        val event = signChecked(EventTemplate<Event>(now(), MagpieSpec.KIND_BLOSSOM_SERVERS, tags, ""))
        val results = nostr.publish(relays, event)
        if (results.values.none { it }) throw IllegalStateException("No relay accepted the server list (${relays.joinToString()})")
        log.lifecycle("Magpie: published Blossom server list ${servers.joinToString()} to ${results.filterValues { it }.size} relays")
        return event
    }

    /**
     * Publishes a NIP-65 relay list (kind 10002) naming [relays] as the signer's relays, to the
     * index relays (where consumers look it up), to those relays, and to the domain's relays.
     */
    public suspend fun createRelayList(
        relays: List<String>,
        settings: ListSettings,
    ): Event {
        val listed = RelayUrls.clean(relays, max = Int.MAX_VALUE)
        require(listed.isNotEmpty()) { "Give at least one relay (wss://…)" }
        val domainRelays = RelayUrls.clean(domainRelaysLenient(settings.group))
        val index = RelayUrls.clean(settings.indexRelays, max = Int.MAX_VALUE)
        val targets = (index + listed + domainRelays).distinct()
        val tags = listed.map { arrayOf("r", it) }.toTypedArray()
        val event = signChecked(EventTemplate<Event>(now(), MagpieSpec.KIND_RELAY_LIST, tags, ""))
        val results = nostr.publish(targets, event)
        if (results.values.none { it }) throw IllegalStateException("No relay accepted the relay list (${targets.joinToString()})")
        if (index.isNotEmpty() && index.none { results[it] == true }) {
            log.warn("Magpie: no index relay accepted the relay list, so consumers that only know your npub can't find your relays yet")
        }
        log.lifecycle("Magpie: published relay list ${listed.joinToString()} to ${results.filterValues { it }.size} relays")
        return event
    }

    private fun contentTypeOf(key: String): String =
        when {
            key.endsWith(".pom") -> "application/xml"
            key.endsWith(".module") || key.endsWith(".json") -> "application/json"
            key.endsWith(".jar") || key.endsWith(".aar") -> "application/java-archive"
            key.endsWith(".asc") -> "application/pgp-signature"
            else -> "application/octet-stream"
        }
}
