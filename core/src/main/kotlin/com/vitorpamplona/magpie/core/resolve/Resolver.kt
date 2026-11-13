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
package com.vitorpamplona.magpie.core.resolve

import com.vitorpamplona.magpie.core.nostr.RelaysUnavailableException
import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.ExpiringCache
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.core.util.StripedMutex
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.MagpiePublicationEvent
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSnapshotEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.ReleaseSelection
import com.vitorpamplona.magpie.spec.WinnerSelection
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.sync.withLock
import java.io.File

public data class ResolverSettings(
    val relays: List<String>,
    val fallbackServers: List<String> = emptyList(),
    val cacheDir: File,
    val offline: Boolean = false,
    /** Misses are remembered this long, so a build that asks twice doesn't query twice. */
    val negativeTtlMs: Long = 30_000,
    /** How long module listings (maven-metadata.xml) and snapshots stay fresh in memory. */
    val listingTtlMs: Long = 30_000,
    /** How long a publisher's kind 10063 stays fresh in memory. */
    val serverListTtlMs: Long = 10 * 60_000,
)

/**
 * Finds releases, snapshots, manifests and blobs for trusted publishers.
 *
 * Releases are immutable: once a winning release is known (from relays, the lock, or the disk
 * cache) it is used forever and relays are not asked again. Manifests and blobs are content
 * addressed and cached forever. Only module listings and snapshots expire.
 */
public class Resolver(
    public val settings: ResolverSettings,
    public val trust: TrustPolicy,
    private val query: RelayQuery,
    public val fetcher: BlobFetcher,
    private val log: MagpieLog,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val releases = ExpiringCache<String, MagpieReleaseEvent>(MAX_ENTRIES, clock)
    private val snapshots = ExpiringCache<String, MagpieSnapshotEvent?>(MAX_ENTRIES, clock)
    private val listings = ExpiringCache<String, List<Event>>(MAX_ENTRIES, clock)
    private val serverLists = ExpiringCache<String, List<String>>(MAX_ENTRIES, clock)
    private val misses = ExpiringCache<String, Unit>(MAX_ENTRIES, clock)
    private val manifests = ExpiringCache<String, Manifest>(MAX_ENTRIES, clock)
    private val locks = StripedMutex()

    private val eventsDir = File(settings.cacheDir, "events")

    public suspend fun publisherFor(group: String): TrustedPublisher? = trust.publisherFor(group)

    // Striped, so nothing nests: a block must never call single() again.
    private suspend fun <T> single(
        key: String,
        block: suspend () -> T,
    ): T = locks.forKey(key).withLock { block() }

    private fun eventFile(
        publisher: HexKey,
        coordinate: Coordinate,
    ) = File(eventsDir, "$publisher/${coordinate.group}/${coordinate.artifact}/${coordinate.version}.json")

    private fun listingFile(
        publisher: HexKey,
        module: ModuleId,
    ) = File(eventsDir, "$publisher/${module.group}/${module.artifact}/_module.json")

    private fun releaseKey(
        publisher: HexKey,
        coordinate: Coordinate,
    ) = "r:$publisher:$coordinate"

    /** The lock first: if it refuses (a frozen lock), nothing is cached and every retry refuses too. */
    private suspend fun accept(
        key: String,
        coordinate: Coordinate,
        release: MagpieReleaseEvent,
    ): MagpieReleaseEvent {
        trust.recordRelease(coordinate, release)
        releases.put(key, release, FOREVER)
        return release
    }

    /**
     * The canonical release of [coordinate] by [publisher], or null if there is none.
     * Honors the lock and records the winner in it, on every use. Throws
     * [com.vitorpamplona.magpie.core.nostr.RelaysUnavailableException] when no relay answers:
     * an outage is not "no such release".
     */
    public suspend fun release(
        coordinate: Coordinate,
        publisher: HexKey,
    ): MagpieReleaseEvent? {
        val key = releaseKey(publisher, coordinate)
        releases.get(key)?.let { return accept(key, coordinate, it) }
        return single(key) {
            releases.get(key)?.let { return@single accept(key, coordinate, it) }
            val locked = trust.lockedManifest(coordinate)
            val cacheFile = eventFile(publisher, coordinate)

            // Disk first: every candidate seen before, selected with this project's lock.
            val cachedEvents = query.readEvents(cacheFile)
            val cached = (WinnerSelection.release(cachedEvents, coordinate.module, coordinate.version, publisher, locked) as? ReleaseSelection.Found)?.release
            if (cached != null) return@single accept(key, coordinate, cached)

            if (!query.online || misses.contains(key)) {
                if (locked != null && !query.online) {
                    throw MagpieSecurityException("magpie.lock pins $coordinate to manifest $locked, which is not in the offline cache.")
                }
                return@single null
            }

            val events = query.releases(coordinate, publisher) + cachedEvents
            when (val selection = WinnerSelection.release(events, coordinate.module, coordinate.version, publisher, locked)) {
                is ReleaseSelection.Found -> {
                    if (selection.shadowed.isNotEmpty()) {
                        log.warn(
                            "$coordinate: ${PublicKeys.npub(publisher)} signed ${selection.shadowed.size + 1} releases; using ${selection.release.id} " +
                                "(${if (locked != null) "pinned by magpie.lock" else "the earliest"}), ignoring ${selection.shadowed.joinToString { it.id }}",
                        )
                    }
                    // Every candidate goes to the shared disk cache; each project selects with its own lock.
                    query.writeEvents(cacheFile, WinnerSelection.releasesFor(events, coordinate.module, coordinate.version, publisher))
                    val winner = accept(key, coordinate, selection.release)
                    shareWithSiblings(winner, coordinate, publisher)
                    winner
                }

                is ReleaseSelection.LockedReleaseMissing -> {
                    throw MagpieSecurityException(
                        "magpie.lock pins $coordinate to manifest ${selection.lockedManifest}, but the relays only have releases of " +
                            (
                                selection.available
                                    .mapNotNull { it.manifest()?.sha256 }
                                    .distinct()
                                    .joinToString()
                                    .ifEmpty { "nothing" }
                            ) +
                            ". Refusing to resolve different files.",
                    )
                }

                ReleaseSelection.NotFound -> {
                    misses.put(key, Unit, settings.negativeTtlMs)
                    null
                }
            }
        }
    }

    /**
     * One release usually covers every module of a Kotlin Multiplatform library: remember it for
     * the others (this build only, and only where the lock agrees), so Gradle asking for the
     * root `.module` and then the platform one costs one relay query, not two. The lock records
     * a module when it is actually resolved.
     */
    private fun shareWithSiblings(
        release: MagpieReleaseEvent,
        coordinate: Coordinate,
        publisher: HexKey,
    ) {
        val manifest = release.manifest()?.sha256 ?: return
        release.modules().filter { it != coordinate.module }.forEach { module ->
            val sibling = runCatching { module.withVersion(coordinate.version) }.getOrNull() ?: return@forEach
            val locked = trust.lockedManifest(sibling)
            if (locked == null || locked == manifest) releases.put(releaseKey(publisher, sibling), release, settings.listingTtlMs)
        }
    }

    /**
     * Fresh events from relays, or, when none answers, what the disk cache has (with a warning).
     * With nothing cached either, the outage propagates.
     */
    private suspend fun onlineOrCached(
        cacheFile: File,
        what: String,
        fetch: suspend () -> List<Event>,
    ): List<Event> {
        val cached = query.readEvents(cacheFile)
        if (!query.online) return cached
        return try {
            fetch() + cached
        } catch (e: RelaysUnavailableException) {
            if (cached.isEmpty()) throw e
            log.warn("${e.message}; using the cached $what")
            cached
        }
    }

    /** The newest snapshot of [coordinate] (a -SNAPSHOT version). Expires after [ResolverSettings.listingTtlMs]. */
    public suspend fun snapshot(
        coordinate: Coordinate,
        publisher: HexKey,
    ): MagpieSnapshotEvent? {
        val key = "s:$publisher:$coordinate"
        if (snapshots.contains(key)) return snapshots.get(key)
        return single(key) {
            if (snapshots.contains(key)) return@single snapshots.get(key)
            val cacheFile = eventFile(publisher, coordinate)
            val events = onlineOrCached(cacheFile, "snapshot of $coordinate") { query.snapshots(coordinate, publisher) }
            val newest = WinnerSelection.snapshot(events, coordinate.module, coordinate.version, publisher)
            if (newest != null) query.writeEvents(cacheFile, listOf(newest))
            snapshots.put(key, newest, if (newest == null) settings.negativeTtlMs else settings.listingTtlMs)
            newest
        }
    }

    /** Every release and snapshot of [module], for metadata. */
    public suspend fun moduleEvents(
        module: ModuleId,
        publisher: HexKey,
    ): List<Event> {
        val key = "m:$publisher:$module"
        listings.get(key)?.let { return it }
        return single(key) {
            listings.get(key)?.let { return@single it }
            val cacheFile = listingFile(publisher, module)
            val events =
                onlineOrCached(cacheFile, "version list of $module") { query.moduleEvents(module, publisher) }
                    .distinctBy { it.id }
                    .also { if (it.isNotEmpty() && query.online) query.writeEvents(cacheFile, it) }
            listings.put(key, events, if (events.isEmpty()) settings.negativeTtlMs else settings.listingTtlMs)
            events
        }
    }

    /** The versions `maven-metadata.xml` lists, honoring locked releases. */
    public suspend fun versions(
        module: ModuleId,
        publisher: HexKey,
    ): List<VersionEntry> {
        val events = moduleEvents(module, publisher)
        val releaseEntries =
            WinnerSelection.releasesByVersion(events, module, publisher).map { winner ->
                val version = winner.version()!!
                val locked = trust.lockedManifest(module.withVersion(version))
                val chosen =
                    if (locked != null && locked != winner.manifest()?.sha256) {
                        WinnerSelection.releasesFor(events, module, version, publisher).firstOrNull { it.manifest()?.sha256 == locked } ?: winner
                    } else {
                        winner
                    }
                VersionEntry(version, chosen.createdAt)
            }
        val snapshotEntries =
            WinnerSelection.snapshotsByVersion(events, module, publisher).map { VersionEntry(it.version()!!, it.createdAt) }
        return releaseEntries + snapshotEntries
    }

    /** The manifest a publication pins, fetched and hash-checked once, then cached forever. */
    public suspend fun manifest(publication: MagpiePublicationEvent): Manifest {
        val pointer = publication.manifest() ?: throw IllegalStateException("Event ${publication.id} has no manifest")
        manifests.get(pointer.sha256)?.let { return it }
        // A missing or absurd size must not mean "no limit": cap what a server can stream at us.
        val limit = pointer.size.takeIf { it in 0..MagpieSpec.MAX_MANIFEST_BYTES } ?: MagpieSpec.MAX_MANIFEST_BYTES
        val file = blob(pointer.sha256, limit, publication)
        val manifest = Manifest.parse(file.readBytes())
        val expected = publication.coordinate()
        if (expected != null && manifest.coordinate.version != expected.version) {
            throw IllegalStateException("Manifest ${pointer.sha256} is for ${manifest.coordinate}, but event ${publication.id} is for $expected")
        }
        manifests.put(pointer.sha256, manifest, FOREVER)
        return manifest
    }

    /**
     * A blob: from the disk cache, else from the publication's server hints, then the publisher's
     * current kind 10063 list, then the configured fallbacks.
     */
    public suspend fun blob(
        sha256: String,
        size: Long,
        publication: MagpiePublicationEvent,
    ): File {
        fetcher.store.get(sha256)?.let { return it }
        if (!query.online && settings.offline) throw BlobNotFoundException(sha256, emptyList(), listOf("offline, and not in the cache"))
        val tried = mutableSetOf<String>()
        val hints = publication.servers().map { it.trimEnd('/') }
        try {
            tried += hints
            return fetcher.fetch(sha256, hints, size)
        } catch (e: BlobNotFoundException) {
            log.info("$sha256 not on the release's servers, trying ${PublicKeys.npub(publication.pubKey)}'s server list")
        }
        val current = serverListOf(publication.pubKey).filter { it !in tried }
        if (current.isNotEmpty()) {
            try {
                tried += current
                return fetcher.fetch(sha256, current, size)
            } catch (e: BlobNotFoundException) {
                log.info("$sha256 not on the publisher's servers, trying fallbacks")
            }
        }
        return fetcher.fetch(sha256, settings.fallbackServers.map { it.trimEnd('/') }.filter { it !in tried }, size)
    }

    private suspend fun serverListOf(publisher: HexKey): List<String> {
        val key = "srv:$publisher"
        serverLists.get(key)?.let { return it }
        // One query per publisher, however many blobs are waiting on it.
        return single(key) {
            serverLists.get(key)?.let { return@single it }
            val servers =
                try {
                    query.blossomServers(publisher)
                } catch (e: Exception) {
                    log.warn("Could not read the server list of ${PublicKeys.npub(publisher)}: ${e.message}")
                    emptyList()
                }
            // An empty answer may be an outage: ask again soon rather than in ten minutes.
            serverLists.put(key, servers, if (servers.isEmpty()) settings.negativeTtlMs else settings.serverListTtlMs)
            servers
        }
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val FOREVER = Long.MAX_VALUE / 4
    }
}
