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
import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSnapshotEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.WinnerSelection
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File

public data class PublishSettings(
    val repoDir: File,
    val group: String,
    val version: String,
    /** Root artifactId; guessed (the shortest) when null. */
    val rootArtifact: String? = null,
    /** Where to read the publisher's kind 10063 and 10002. */
    val indexerRelays: List<String>,
    /** Archival relays that get every release besides the publisher's NIP-65 write relays. */
    val publishRelays: List<String> = emptyList(),
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

/** The publisher's Blossom servers (kind 10063) and NIP-65 write relays. */
public data class PublisherContext(
    val pubKey: HexKey,
    val servers: List<String>,
    val writeRelays: List<String>,
)

public class MissingServerListException(
    npub: String,
) : IllegalStateException(
        "$npub has no Blossom server list (kind 10063), so Magpie doesn't know where to upload. Create one with\n" +
            "    ./gradlew createNostrServerList --server=https://blossom.example.com\n" +
            "(any BUD-02 server; repeat --server for more), then publish again.",
    )

public class VersionAlreadyReleasedException(
    message: String,
) : IllegalStateException(message)

/**
 * Publishes a local Maven repository as a Magpie release: hash every file, make sure each blob
 * can live on at least one of the publisher's Blossom servers (BUD-06), upload what's missing
 * under one BUD-02 authorization, upload the manifest, sign the release and send it to the
 * publisher's write relays and the archival relays. Nothing is signed until every check passes.
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
) {
    private fun now() = clock.nowMillis() / 1000

    public suspend fun context(indexerRelays: List<String>): PublisherContext {
        val pubKey = signer.pubKey()
        val events =
            nostr.query(
                indexerRelays,
                listOf(Filter(kinds = listOf(MagpieSpec.KIND_BLOSSOM_SERVERS, MagpieSpec.KIND_RELAY_LIST), authors = listOf(pubKey))),
            )
        val serverList = events.filter { it.kind == MagpieSpec.KIND_BLOSSOM_SERVERS && it.pubKey == pubKey }.maxByOrNull { it.createdAt }
        val relayList = events.filter { it.kind == MagpieSpec.KIND_RELAY_LIST && it.pubKey == pubKey }.maxByOrNull { it.createdAt }
        val servers =
            serverList
                ?.tags
                ?.filter { it.size >= 2 && it[0] == "server" }
                ?.map { it[1].trimEnd('/') }
                ?.distinct()
                .orEmpty()
        if (servers.isEmpty()) throw MissingServerListException(PublicKeys.npub(pubKey))
        val writeRelays =
            relayList
                ?.tags
                ?.filter { it.size >= 2 && it[0] == "r" && (it.size == 2 || it[2] == "write") }
                ?.map { it[1] }
                .orEmpty()
        return PublisherContext(pubKey, servers, writeRelays)
    }

    public suspend fun publish(settings: PublishSettings): PublishResult {
        val root = settings.rootArtifact ?: RepoScanner.guessRootArtifact(settings.repoDir, settings.group, settings.version)
        val scanned = RepoScanner.scan(settings.repoDir, settings.group, settings.version, root, log)
        val manifest = scanned.manifest
        log.lifecycle("Magpie: ${manifest.coordinate} has ${manifest.files.size} files in ${manifest.artifacts.size} modules")

        keyCheck?.verify(settings.group, signer.pubKey())
        val context = context(settings.indexerRelays)
        val relays = (context.writeRelays + settings.publishRelays).ifEmpty { settings.indexerRelays }.distinct()
        if (!manifest.coordinate.isSnapshot) refuseOverwrite(manifest, context, (relays + settings.indexerRelays).distinct())

        val files =
            scanned.files.map { (key, file) -> BlobSource(manifest[key]!!.sha256, file.length(), contentTypeOf(key), file = file) }

        if (settings.partial) {
            val hosted = host(files, context.servers, settings.dryRun)
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
        return finish(manifest, files, context, relays, settings.dryRun)
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
        log.lifecycle("Magpie: merged ${partials.size} partial manifests into ${manifest.files.size} files")

        keyCheck?.verify(settings.group, signer.pubKey())
        val context = context(settings.indexerRelays)
        val relays = (context.writeRelays + settings.publishRelays).ifEmpty { settings.indexerRelays }.distinct()
        if (!manifest.coordinate.isSnapshot) refuseOverwrite(manifest, context, (relays + settings.indexerRelays).distinct())

        // Every blob must already be on a server, and every .module reference must be in the set.
        val store = BlobStore(File(dir, ".blobs"))
        val fetcher = BlobFetcher(http, store, log)
        val absent = mutableListOf<String>()
        coroutineScope {
            val gate = Semaphore(parallelism)
            manifest.files
                .map { (key, file) ->
                    async {
                        gate.withPermit {
                            if (context.servers.none { blossom.has(it, file.sha256) }) synchronized(absent) { absent += key }
                        }
                    }
                }.awaitAll()
        }
        if (absent.isNotEmpty()) {
            throw IllegalStateException("Refusing to sign: these files are on none of ${context.servers}:\n  " + absent.sorted().joinToString("\n  "))
        }
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
        return finish(manifest, emptyList(), context, relays, settings.dryRun)
    }

    private suspend fun finish(
        manifest: Manifest,
        files: List<BlobSource>,
        context: PublisherContext,
        relays: List<String>,
        dryRun: Boolean,
    ): PublishResult {
        val manifestBytes = manifest.toBytes()
        val manifestBlob = BlobSource(manifest.sha256Hex(), manifestBytes.size.toLong(), MagpieSpec.MANIFEST_CONTENT_TYPE, bytes = manifestBytes)
        val hosted = host(files + manifestBlob, context.servers, dryRun)
        if (dryRun) {
            log.lifecycle("Magpie: dry run; ${manifest.coordinate} checks passed, nothing uploaded or signed")
            return PublishResult(manifest.coordinate.toString(), manifestBlob.sha256, null, 0, signer.signatures, emptyList())
        }

        val template: EventTemplate<*> =
            if (manifest.coordinate.isSnapshot) {
                MagpieSnapshotEvent.build(manifest, manifestBlob.sha256, manifestBlob.size, servers = hosted.servers, createdAt = now())
            } else {
                MagpieReleaseEvent.build(manifest, manifestBlob.sha256, manifestBlob.size, servers = hosted.servers, createdAt = now())
            }
        val event = signer.sign(template)
        val results = nostr.publish(relays, event)
        val accepted = results.filterValues { it }.keys.toList()
        if (accepted.isEmpty()) {
            throw IllegalStateException(
                "No relay accepted ${manifest.coordinate} (${results.keys.joinToString()}). The signed event, to publish by hand:\n${event.toJson()}",
            )
        }
        log.lifecycle(
            "Magpie: published ${manifest.coordinate} as ${event.id} to ${accepted.size}/${results.size} relays " +
                "(${hosted.uploaded} blobs uploaded, ${signer.signatures} signatures)",
        )
        return PublishResult(manifest.coordinate.toString(), manifestBlob.sha256, event.id, hosted.uploaded, signer.signatures, accepted)
    }

    /** Fails if any module of [manifest] already has a release at this version by this key. */
    private suspend fun refuseOverwrite(
        manifest: Manifest,
        context: PublisherContext,
        relays: List<String>,
    ) {
        val modules = manifest.modules()
        val existing =
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
        val clash =
            modules.firstNotNullOfOrNull { module ->
                WinnerSelection.releasesFor(existing, module, manifest.coordinate.version, context.pubKey).firstOrNull()?.let { module to it }
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
        val servers: List<String>,
    )

    /**
     * Makes every blob present on at least one server. Checks first (HEAD and BUD-06 preflight),
     * so a blob no server will take fails the publish before anything is signed; then uploads
     * with one authorization for all hashes, falling back to one per blob where a server
     * refuses the shared one.
     */
    private suspend fun host(
        blobs: List<BlobSource>,
        servers: List<String>,
        dryRun: Boolean,
    ): Hosted {
        val unique = blobs.distinctBy { it.sha256 }
        // server -> blobs it lacks; blob -> servers that have it or say they'd take it.
        val lacking = mutableMapOf<String, MutableList<BlobSource>>()
        val hostable = unique.associate { it.sha256 to mutableListOf<String>() }
        val refusals = mutableMapOf<String, MutableList<String>>()
        coroutineScope {
            val gate = Semaphore(parallelism)
            servers
                .flatMap { server ->
                    unique.map { blob ->
                        async {
                            gate.withPermit {
                                if (blossom.has(server, blob.sha256)) {
                                    synchronized(hostable) { hostable.getValue(blob.sha256) += server }
                                } else {
                                    when (val preflight = blossom.preflight(server, blob)) {
                                        is Preflight.Refused -> {
                                            synchronized(refusals) { refusals.getOrPut(blob.sha256) { mutableListOf() } += "$server: ${preflight.status} ${preflight.reason}" }
                                        }

                                        else -> {
                                            synchronized(hostable) {
                                                hostable.getValue(blob.sha256) += server
                                                lacking.getOrPut(server) { mutableListOf() } += blob
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
        }
        val homeless = unique.filter { hostable.getValue(it.sha256).isEmpty() }
        if (homeless.isNotEmpty()) {
            throw IllegalStateException(
                "No Blossom server can host these blobs, so nothing was signed:\n  " +
                    homeless.joinToString("\n  ") { "${it.sha256} (${it.size} bytes): ${refusals[it.sha256].orEmpty().joinToString("; ")}" },
            )
        }
        if (dryRun || lacking.isEmpty()) {
            return Hosted(0, servers.filter { s -> unique.all { s in hostable.getValue(it.sha256) } }.ifEmpty { servers })
        }

        val toUpload = lacking.values.flatten().distinctBy { it.sha256 }
        val auth = signer.sign(BlossomClient.uploadAuth(toUpload.map { it.sha256 }, lacking.keys, now()))
        val present = unique.associate { blob -> blob.sha256 to hostable.getValue(blob.sha256).filter { s -> lacking[s]?.none { it.sha256 == blob.sha256 } ?: true }.toMutableSet() }
        var uploaded = 0
        for ((server, list) in lacking) {
            var perBlob = false
            for (blob in list) {
                var outcome = if (perBlob) null else blossom.upload(server, blob, auth)
                if (outcome == null || (!outcome.ok && (outcome.status == 401 || outcome.status == 403) && toUpload.size > 1)) {
                    if (!perBlob && outcome != null) log.info("$server refused the shared upload authorization (${outcome.reason}); signing one per blob")
                    perBlob = true
                    val single = signer.sign(BlossomClient.uploadAuth(listOf(blob.sha256), listOf(server), now()))
                    outcome = blossom.upload(server, blob, single)
                }
                if (outcome.ok) {
                    uploaded++
                    present.getValue(blob.sha256) += server
                } else {
                    log.warn("Upload of ${blob.sha256} to $server failed: ${outcome.status} ${outcome.reason}")
                }
            }
        }
        val stillMissing = unique.filter { present.getValue(it.sha256).isEmpty() }
        if (stillMissing.isNotEmpty()) {
            throw IllegalStateException("These blobs could not be uploaded to any server, so the release was not signed:\n  " + stillMissing.joinToString("\n  ") { it.sha256 })
        }
        val complete = servers.filter { s -> unique.all { s in present.getValue(it.sha256) } }
        return Hosted(uploaded, complete.ifEmpty { servers.filter { s -> present.values.any { s in it } } })
    }

    /** Publishes a kind 10063 Blossom server list. */
    public suspend fun createServerList(
        servers: List<String>,
        relays: List<String>,
    ): Event {
        require(servers.isNotEmpty()) { "Give at least one Blossom server" }
        val tags = servers.map { arrayOf("server", it.trimEnd('/')) }.toTypedArray()
        val event = signer.sign(EventTemplate<Event>(now(), MagpieSpec.KIND_BLOSSOM_SERVERS, tags, ""))
        val results = nostr.publish(relays, event)
        if (results.values.none { it }) throw IllegalStateException("No relay accepted the server list (${relays.joinToString()})")
        log.lifecycle("Magpie: published Blossom server list ${servers.joinToString()} to ${results.filterValues { it }.size} relays")
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
