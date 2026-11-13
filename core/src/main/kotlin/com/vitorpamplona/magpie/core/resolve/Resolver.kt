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

import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.MagpiePublicationEvent
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSnapshotEvent
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.ReleaseSelection
import com.vitorpamplona.magpie.spec.WinnerSelection
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private class Timed<T>(
        val value: T,
        val expiresAt: Long,
    )

    private val releases = ConcurrentHashMap<String, MagpieReleaseEvent>()
    private val snapshots = ConcurrentHashMap<String, Timed<MagpieSnapshotEvent?>>()
    private val listings = ConcurrentHashMap<String, Timed<List<Event>>>()
    private val serverLists = ConcurrentHashMap<String, Timed<List<String>>>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val manifests = ConcurrentHashMap<String, Manifest>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val eventsDir = File(settings.cacheDir, "events")

    public suspend fun publisherFor(group: String): TrustedPublisher? = trust.publisherFor(group)

    private suspend fun <T> single(
        key: String,
        block: suspend () -> T,
    ): T = locks.computeIfAbsent(key) { Mutex() }.withLock { block() }

    private fun missed(key: String): Boolean {
        val until = misses[key] ?: return false
        if (until > clock.nowMillis()) return true
        misses.remove(key, until)
        return false
    }

    private fun miss(key: String) {
        misses[key] = clock.nowMillis() + settings.negativeTtlMs
    }

    private fun eventFile(
        publisher: HexKey,
        coordinate: Coordinate,
    ) = File(eventsDir, "$publisher/${coordinate.group}/${coordinate.artifact}/${coordinate.version}.json")

    private fun listingFile(
        publisher: HexKey,
        module: ModuleId,
    ) = File(eventsDir, "$publisher/${module.group}/${module.artifact}/_module.json")

    /**
     * The canonical release of [coordinate] by [publisher], or null if there is none.
     * Honors the lock; records the winner in it.
     */
    public suspend fun release(
        coordinate: Coordinate,
        publisher: HexKey,
    ): MagpieReleaseEvent? {
        val key = "r:$publisher:$coordinate"
        releases[key]?.let { return it }
        return single(key) {
            releases[key]?.let { return@single it }
            val locked = trust.lockedManifest(coordinate)
            val cacheFile = eventFile(publisher, coordinate)

            // Disk first: a release we already accepted never changes.
            val cached =
                (WinnerSelection.release(query.readEvents(cacheFile), coordinate.module, coordinate.version, publisher, locked) as? ReleaseSelection.Found)?.release
            if (cached != null) {
                releases[key] = cached
                trust.recordRelease(coordinate, cached)
                return@single cached
            }
            if (!query.online || missed(key)) {
                if (locked != null && !query.online) {
                    throw MagpieSecurityException("magpie.lock pins $coordinate to manifest $locked, which is not in the offline cache.")
                }
                return@single null
            }

            val events = query.releases(coordinate, publisher)
            when (val selection = WinnerSelection.release(events, coordinate.module, coordinate.version, publisher, locked)) {
                is ReleaseSelection.Found -> {
                    if (selection.shadowed.isNotEmpty()) {
                        log.warn(
                            "$coordinate: ${PublicKeys.npub(publisher)} signed ${selection.shadowed.size + 1} releases; using ${selection.release.id} " +
                                "(${if (locked != null) "pinned by magpie.lock" else "the earliest"}), ignoring ${selection.shadowed.joinToString { it.id }}",
                        )
                    }
                    query.writeEvents(cacheFile, listOf(selection.release))
                    releases[key] = selection.release
                    trust.recordRelease(coordinate, selection.release)
                    selection.release
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
                    miss(key)
                    null
                }
            }
        }
    }

    /** The newest snapshot of [coordinate] (a -SNAPSHOT version). Expires after [ResolverSettings.listingTtlMs]. */
    public suspend fun snapshot(
        coordinate: Coordinate,
        publisher: HexKey,
    ): MagpieSnapshotEvent? {
        val key = "s:$publisher:$coordinate"
        snapshots[key]?.takeIf { it.expiresAt > clock.nowMillis() }?.let { return it.value }
        return single(key) {
            snapshots[key]?.takeIf { it.expiresAt > clock.nowMillis() }?.let { return@single it.value }
            val cacheFile = eventFile(publisher, coordinate)
            val events =
                if (query.online) {
                    query.snapshots(coordinate, publisher) + query.readEvents(cacheFile)
                } else {
                    query.readEvents(cacheFile)
                }
            val newest = WinnerSelection.snapshot(events, coordinate.module, coordinate.version, publisher)
            if (newest != null) query.writeEvents(cacheFile, listOf(newest))
            snapshots[key] = Timed(newest, clock.nowMillis() + if (newest == null) settings.negativeTtlMs else settings.listingTtlMs)
            newest
        }
    }

    /** Every release and snapshot of [module], for metadata. */
    public suspend fun moduleEvents(
        module: ModuleId,
        publisher: HexKey,
    ): List<Event> {
        val key = "m:$publisher:$module"
        listings[key]?.takeIf { it.expiresAt > clock.nowMillis() }?.let { return it.value }
        return single(key) {
            listings[key]?.takeIf { it.expiresAt > clock.nowMillis() }?.let { return@single it.value }
            val cacheFile = listingFile(publisher, module)
            val events =
                if (query.online) {
                    (query.moduleEvents(module, publisher) + query.readEvents(cacheFile)).distinctBy { it.id }.also {
                        if (it.isNotEmpty()) query.writeEvents(cacheFile, it)
                    }
                } else {
                    query.readEvents(cacheFile)
                }
            listings[key] = Timed(events, clock.nowMillis() + if (events.isEmpty()) settings.negativeTtlMs else settings.listingTtlMs)
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
        manifests[pointer.sha256]?.let { return it }
        val file = blob(pointer.sha256, pointer.size, publication)
        val manifest = Manifest.parse(file.readBytes())
        val expected = publication.coordinate()
        if (expected != null && manifest.coordinate.version != expected.version) {
            throw IllegalStateException("Manifest ${pointer.sha256} is for ${manifest.coordinate}, but event ${publication.id} is for $expected")
        }
        manifests[pointer.sha256] = manifest
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
        serverLists[publisher]?.takeIf { it.expiresAt > clock.nowMillis() }?.let { return it.value }
        val servers =
            try {
                query.blossomServers(publisher)
            } catch (e: Exception) {
                log.warn("Could not read the server list of ${PublicKeys.npub(publisher)}: ${e.message}")
                emptyList()
            }
        serverLists[publisher] = Timed(servers, clock.nowMillis() + settings.serverListTtlMs)
        return servers
    }
}
