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
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin

/**
 * Checks an event's id and BIP-340 signature with the pure-Kotlin secp256k1, so resolution never
 * loads a native library (the Gradle daemon can only load one copy per JVM).
 */
public object EventVerification {
    public fun isValid(event: Event): Boolean =
        try {
            event.id.length == 64 &&
                event.pubKey.length == 64 &&
                event.sig.length == 128 &&
                EventHasher.hashIdCheck(event.id, event.pubKey, event.createdAt, event.kind, event.tags, event.content) &&
                Secp256k1InstanceKotlin.verifySchnorr(Hex.decode(event.sig), Hex.decode(event.id), Hex.decode(event.pubKey))
        } catch (e: Exception) {
            false
        }
}

/** The outcome of picking the canonical release of one version. */
public sealed interface ReleaseSelection {
    public data class Found(
        val release: MagpieReleaseEvent,
        /** Other releases for the same version that lost (later, or a lock pinned another). */
        val shadowed: List<MagpieReleaseEvent>,
    ) : ReleaseSelection

    public data object NotFound : ReleaseSelection

    /** The lock file pins [lockedId] but no such release (by the pinned publisher) was found. */
    public data class LockedReleaseMissing(
        val lockedId: String,
        val available: List<MagpieReleaseEvent>,
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
    ): List<MagpieReleaseEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MagpieReleaseEvent }
            .filter { it.pubKey == publisher && it.isWellFormed() && it.covers(module, version) }
            .distinctBy { it.id }
            .sortedWith(RELEASE_ORDER)
            .toList()

    public fun release(
        events: Collection<Event>,
        module: ModuleId,
        version: String,
        publisher: HexKey,
        lockedId: String? = null,
    ): ReleaseSelection {
        val candidates = releasesFor(events, module, version, publisher)
        if (lockedId != null) {
            val locked = candidates.firstOrNull { it.id == lockedId } ?: return ReleaseSelection.LockedReleaseMissing(lockedId, candidates)
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
    ): MagpieSnapshotEvent? =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MagpieSnapshotEvent }
            .filter { it.pubKey == publisher && it.isWellFormed() && it.covers(module, version) }
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
    ): List<MagpieReleaseEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MagpieReleaseEvent }
            .filter { it.pubKey == publisher && it.isWellFormed() && module.key in it.moduleKeys() }
            .distinctBy { it.id }
            .sortedWith(RELEASE_ORDER)
            .distinctBy { it.version() }
            .toList()

    /** Every snapshot version of [module] the publisher has, newest build of each. */
    public fun snapshotsByVersion(
        events: Collection<Event>,
        module: ModuleId,
        publisher: HexKey,
    ): List<MagpieSnapshotEvent> =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MagpieSnapshotEvent }
            .filter { it.pubKey == publisher && it.isWellFormed() && module.key in it.moduleKeys() }
            .sortedWith(SNAPSHOT_ORDER)
            .distinctBy { it.version() }
            .sortedWith(RELEASE_ORDER)
            .toList()

    /** The publisher's yank of [release], if any. */
    public fun yankOf(
        release: MagpieReleaseEvent,
        events: Collection<Event>,
    ): MagpieYankEvent? =
        events
            .asSequence()
            .mapNotNull { MagpieEvents.wrap(it) as? MagpieYankEvent }
            .filter { it.appliesTo(release) }
            .sortedWith(RELEASE_ORDER)
            .firstOrNull()
}
