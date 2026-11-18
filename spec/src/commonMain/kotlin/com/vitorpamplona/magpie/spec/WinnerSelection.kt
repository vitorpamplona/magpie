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
package com.vitorpamplona.magpie.spec

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot.MavenSnapshotEvent
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin

/**
 * Checks an event's id and BIP-340 signature with the pure-Kotlin secp256k1, so resolution never
 * loads a native library (the Gradle daemon can only load one copy per JVM).
 */
public object EventVerification {
    public fun isValid(event: Event): Boolean =
        try {
            isLowerHex(event.id, 64) &&
                isLowerHex(event.pubKey, 64) &&
                isLowerHex(event.sig, 128) &&
                EventHasher.hashIdCheck(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content) &&
                Secp256k1InstanceKotlin.verifySchnorr(Hex.decode(event.sig), Hex.decode(event.id), Hex.decode(event.pubKey))
        } catch (e: Exception) {
            false
        }
}

// Quartz's hex decoder tolerates non-hex characters; a rewritten signature must not verify.
private fun isLowerHex(
    value: String,
    length: Int,
): Boolean = value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

/** The outcome of picking the canonical release of one version. */
public sealed interface ReleaseSelection {
    public data class Found(
        val release: MavenReleaseEvent,
        /** Other releases for the same version that lost (later, or a lock pinned another manifest). */
        val shadowed: List<MavenReleaseEvent>,
    ) : ReleaseSelection

    public data object NotFound : ReleaseSelection

    /** The lock file pins [lockedManifest] but no release of it (by the trusted publisher) was found. */
    public data class LockedReleaseMissing(
        val lockedManifest: String,
        val available: List<MavenReleaseEvent>,
    ) : ReleaseSelection
}

/**
 * Which event is canonical. Releases are immutable: for a group:artifact:version the earliest
 * release by `created_at` wins, ties going to the lowest id, so a later event can never replace
 * a version. Snapshots are addressable: the newest wins (ties: lowest id, as NIP-01 says).
 *
 * Every function takes the events as they came from any number of relays and filters them
 * itself: wrong kind, wrong author, malformed, or not covering the module are dropped. Callers
 * must still drop events whose signature fails [EventVerification].
 */
public object WinnerSelection {
    public val RELEASE_ORDER: Comparator<Event> = compareBy<Event>({ it.createdAt }, { it.id })

    public val SNAPSHOT_ORDER: Comparator<Event> = compareByDescending<Event> { it.createdAt }.thenBy { it.id }

    public fun releasesFor(
        events: Collection<Event>,
        module: ModuleId,
        version: String,
        publisher: HexKey,
    ): List<MavenReleaseEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MavenReleaseEvent }
            .filter { it.pubKey == publisher && it.isUsable() && it.covers(module, version) }
            .distinctBy { it.id }
            .sortedWith(RELEASE_ORDER)
            .toList()

    /**
     * The canonical release. With [lockedManifest] (from the lock file) only releases of that
     * manifest count, earliest first; none is [ReleaseSelection.LockedReleaseMissing].
     */
    public fun release(
        events: Collection<Event>,
        module: ModuleId,
        version: String,
        publisher: HexKey,
        lockedManifest: String? = null,
    ): ReleaseSelection {
        val candidates = releasesFor(events, module, version, publisher)
        if (lockedManifest != null) {
            val locked =
                candidates.firstOrNull { it.manifest()?.sha256 == lockedManifest }
                    ?: return ReleaseSelection.LockedReleaseMissing(lockedManifest, candidates)
            return ReleaseSelection.Found(locked, candidates - locked)
        }
        val winner = candidates.firstOrNull() ?: return ReleaseSelection.NotFound
        return ReleaseSelection.Found(winner, candidates.drop(1))
    }

    public fun snapshot(
        events: Collection<Event>,
        module: ModuleId,
        version: String,
        publisher: HexKey,
    ): MavenSnapshotEvent? =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MavenSnapshotEvent }
            .filter { it.pubKey == publisher && it.isUsable() && it.covers(module, version) }
            .sortedWith(SNAPSHOT_ORDER)
            .firstOrNull()

    /**
     * The winning release of every version of [module], ordered by the winner's `created_at`
     * (Maven metadata lists versions in publication order).
     */
    public fun releasesByVersion(
        events: Collection<Event>,
        module: ModuleId,
        publisher: HexKey,
    ): List<MavenReleaseEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MavenReleaseEvent }
            .filter { it.pubKey == publisher && it.isUsable() && module.key in it.moduleKeys() }
            .distinctBy { it.id }
            .sortedWith(RELEASE_ORDER)
            .distinctBy { it.version() }
            .toList()

    /**
     * Every release of [module] by [publisher], grouped by version in one pass, each group
     * earliest first; groups ordered by their earliest release.
     */
    public fun releasesGroupedByVersion(
        events: Collection<Event>,
        module: ModuleId,
        publisher: HexKey,
    ): Map<String, List<MavenReleaseEvent>> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MavenReleaseEvent }
            .filter { it.pubKey == publisher && module.key in it.moduleKeys() && it.isUsable() }
            .distinctBy { it.id }
            .sortedWith(RELEASE_ORDER)
            .groupBy { it.version()!! }

    /** Every snapshot version of [module] the publisher has, newest build of each. */
    public fun snapshotsByVersion(
        events: Collection<Event>,
        module: ModuleId,
        publisher: HexKey,
    ): List<MavenSnapshotEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MavenSnapshotEvent }
            .filter { it.pubKey == publisher && it.isUsable() && module.key in it.moduleKeys() }
            .sortedWith(SNAPSHOT_ORDER)
            .distinctBy { it.version() }
            .sortedWith(RELEASE_ORDER)
            .toList()
}
