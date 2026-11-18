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
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot.MavenSnapshotEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WinnerSelectionTest {
    private val alice = NostrSignerSync(KeyPair())
    private val mallory = NostrSignerSync(KeyPair())
    private val module = ModuleId("com.example", "lib")

    private fun manifest(
        version: String,
        seed: Char,
    ) = Manifest(
        Coordinate("com.example", "lib", version),
        mapOf("lib/$version/lib-$version.jar" to ManifestFile(seed.toString().repeat(64), 1)),
    )

    private fun <T : Event> NostrSignerSync.signed(template: EventTemplate<T>): Event = MagpieEvents.wrap(sign<Event>(template.createdAt, template.kind, template.tags, template.content))!!

    private fun release(
        signer: NostrSignerSync,
        version: String,
        createdAt: Long,
        seed: Char = 'a',
    ) = signer.signed(MagpieEvents.release(manifest(version, seed), createdAt = createdAt)) as MavenReleaseEvent

    @Test
    fun earliestReleaseWins() {
        val first = release(alice, "1.0", 100, 'a')
        val later = release(alice, "1.0", 200, 'b')
        val result = assertIs<ReleaseSelection.Found>(WinnerSelection.release(listOf(later, first), module, "1.0", alice.pubKey))
        assertEquals(first.id, result.release.id)
        assertEquals(listOf(later.id), result.shadowed.map { it.id })
    }

    @Test
    fun tiesGoToTheLowestId() {
        val one = release(alice, "1.0", 100, 'a')
        val two = release(alice, "1.0", 100, 'b')
        val expected = listOf(one, two).minBy { it.id }
        val result = assertIs<ReleaseSelection.Found>(WinnerSelection.release(listOf(one, two), module, "1.0", alice.pubKey))
        assertEquals(expected.id, result.release.id)
        val reversed = assertIs<ReleaseSelection.Found>(WinnerSelection.release(listOf(two, one), module, "1.0", alice.pubKey))
        assertEquals(expected.id, reversed.release.id)
    }

    @Test
    fun otherAuthorsAreIgnored() {
        val forged = release(mallory, "1.0", 1, 'f')
        val real = release(alice, "1.0", 100, 'a')
        val result = assertIs<ReleaseSelection.Found>(WinnerSelection.release(listOf(forged, real), module, "1.0", alice.pubKey))
        assertEquals(real.id, result.release.id)
        assertEquals(ReleaseSelection.NotFound, WinnerSelection.release(listOf(forged), module, "1.0", alice.pubKey))
    }

    @Test
    fun lockOverridesEarliest() {
        val backdated = release(alice, "1.0", 50, 'f')
        val locked = release(alice, "1.0", 100, 'a')
        val result =
            assertIs<ReleaseSelection.Found>(
                WinnerSelection.release(listOf(backdated, locked), module, "1.0", alice.pubKey, lockedManifest = locked.manifest()!!.sha256),
            )
        assertEquals(locked.id, result.release.id)
        assertIs<ReleaseSelection.LockedReleaseMissing>(
            WinnerSelection.release(listOf(backdated), module, "1.0", alice.pubKey, lockedManifest = locked.manifest()!!.sha256),
        )
    }

    @Test
    fun aLockedManifestMatchesAnyReleaseOfIt() {
        // A release re-signed later (say, after a key rotation re-published the same manifest)
        // still satisfies the lock; a release of other files doesn't, even if earlier.
        val original = release(alice, "1.0", 100, 'a')
        val resigned = release(alice, "1.0", 300, 'a')
        val other = release(alice, "1.0", 50, 'b')
        val pinned = original.manifest()!!.sha256
        val result =
            assertIs<ReleaseSelection.Found>(
                WinnerSelection.release(listOf(other, resigned), module, "1.0", alice.pubKey, lockedManifest = pinned),
            )
        assertEquals(resigned.id, result.release.id)
    }

    @Test
    fun versionsAreListedInPublicationOrder() {
        val v2 = release(alice, "2.0", 300)
        val v1 = release(alice, "1.0", 100)
        val v1dup = release(alice, "1.0", 400, 'b')
        val v10 = release(alice, "1.10", 200)
        val list = WinnerSelection.releasesByVersion(listOf(v2, v1dup, v1, v10), module, alice.pubKey)
        assertEquals(listOf("1.0", "1.10", "2.0"), list.map { it.version() })
        assertEquals(v1.id, list.first().id)
    }

    @Test
    fun newestSnapshotWins() {
        val m = Manifest(Coordinate("com.example", "lib", "1.0-SNAPSHOT"), emptyMap())
        val old = alice.signed(MagpieEvents.snapshot(m, createdAt = 100)) as MavenSnapshotEvent
        val new = alice.signed(MagpieEvents.snapshot(m, createdAt = 200)) as MavenSnapshotEvent
        assertEquals(new.id, WinnerSelection.snapshot(listOf(old, new), module, "1.0-SNAPSHOT", alice.pubKey)?.id)
        assertEquals("com.example:lib:1.0-SNAPSHOT", new.dTag())
        assertTrue(new.isUsable())
    }

    @Test
    fun signaturesVerifyWithThePureKotlinSecp256k1() {
        val release = release(alice, "1.0", 100)
        assertTrue(EventVerification.isValid(release))
        val tampered = MavenReleaseEvent(release.id, release.pubKey, release.createdAt + 1, release.tags, release.content, release.sig)
        assertFalse(EventVerification.isValid(tampered))
        val wrongSig = MavenReleaseEvent(release.id, mallory.pubKey, release.createdAt, release.tags, release.content, release.sig)
        assertFalse(EventVerification.isValid(wrongSig))
    }

    @Test
    fun releaseTagsCarryModulesVersionManifestAndPrimaries() {
        val m =
            Manifest(
                Coordinate("com.example", "lib", "1.0"),
                mapOf(
                    "lib/1.0/lib-1.0.module" to ManifestFile("1".repeat(64), 1),
                    "lib-jvm/1.0/lib-jvm-1.0.jar" to ManifestFile("2".repeat(64), 1),
                    "lib-jvm/1.0/lib-jvm-1.0-sources.jar" to ManifestFile("3".repeat(64), 1),
                ),
            )
        val event =
            alice.signed(
                MagpieEvents.release(m, servers = listOf("https://blossom.example"), createdAt = 1),
            ) as MavenReleaseEvent
        assertEquals(listOf("com.example:lib", "com.example:lib-jvm"), event.moduleKeys())
        assertEquals("1.0", event.version())
        assertEquals(m.sha256Hex(), event.manifest()?.sha256)
        assertEquals(setOf(m.sha256Hex(), "1".repeat(64), "2".repeat(64)), event.hashes().toSet())
        assertEquals(listOf("https://blossom.example"), event.servers())
        assertEquals(Coordinate("com.example", "lib", "1.0"), event.rootCoordinate())
    }

    @Test
    fun releaseStaysUnderTheSizeBudget() {
        val files = (0 until 400).associate { i -> "lib/1.0/lib-1.0-c$i.jar" to ManifestFile(i.toString(16).padStart(64, '0'), 1) }
        val m = Manifest(Coordinate("com.example", "lib", "1.0"), files)
        val event = alice.signed(MagpieEvents.release(m, createdAt = 1))
        assertTrue(event.toJson().length <= MagpieSpec.MAX_EVENT_BYTES, "size ${event.toJson().length}")
        assertTrue(m.sha256Hex() in (event as MavenReleaseEvent).hashes())
    }

    @Test
    fun lockFileRoundTrip() {
        val lock =
            LockFile()
                .withRelease(Coordinate("com.b", "x", "1.0"), "e".repeat(64), "d".repeat(64))
                .withPublisher("com.b", LockedPublisher(alice.pubKey, "nip05:alice@b.com"))
                .withRelease(Coordinate("com.a", "x", "1.0"), "f".repeat(64))
        val text = lock.render()
        assertEquals(lock, LockFile.parse(text))
        assertTrue(text.indexOf("com.a:x") < text.indexOf("com.b:x"))
    }

    @Test
    fun publicKeysRefuseNsec() {
        val npub = PublicKeys.npub(alice.pubKey)
        assertEquals(alice.pubKey, PublicKeys.parseOrNull(npub))
        assertEquals(alice.pubKey, PublicKeys.parseOrNull(alice.pubKey))
        assertNull(PublicKeys.parseOrNull("nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"))
    }

    @Test
    fun nonHexSignaturesDoNotVerify() {
        val r = release(alice, "1.0", 100)
        // A hex pair rewritten as non-hex must fail, whatever a lenient decoder makes of it.
        val forged = "zz" + r.sig.substring(2)
        assertFalse(EventVerification.isValid(MavenReleaseEvent(r.id, r.pubKey, r.createdAt, r.tags, r.content, forged)))
        assertFalse(EventVerification.isValid(MavenReleaseEvent(r.id, r.pubKey, r.createdAt, r.tags, r.content, r.sig.uppercase())))
    }

    @Test
    fun aManifestTagWithoutASizeIsNotWellFormed() {
        val m = manifest("1.0", 'a')
        val event = release(alice, "1.0", 100)
        val tags = event.tags.map { if (it[0] == MagpieSpec.TAG_MANIFEST) arrayOf(it[0], it[1]) else it }.toTypedArray()
        val unsized = alice.signed(EventTemplate<MavenReleaseEvent>(100, MagpieSpec.KIND_RELEASE, tags, "")) as MavenReleaseEvent
        assertFalse(unsized.isUsable())
        assertEquals(m.sha256Hex(), unsized.manifest()?.sha256)
    }

    @Test
    fun releasesGroupByVersionInOnePass() {
        val a = release(alice, "1.0", 100, 'a')
        val b = release(alice, "1.0", 50, 'b')
        val c = release(alice, "2.0", 200, 'c')
        val grouped = WinnerSelection.releasesGroupedByVersion(listOf(a, c, b), module, alice.pubKey)
        assertEquals(listOf("1.0", "2.0"), grouped.keys.toList())
        assertEquals(listOf(b.id, a.id), grouped.getValue("1.0").map { it.id })
    }

    @Test
    fun anNsecIsNeverEchoed() {
        val secret = "nostr:nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> { PublicKeys.parse(secret) }
        assertTrue("nsec1" !in error.message!!, error.message)
        assertTrue("private key" in error.message!!)
    }

    @Test
    fun numericGroupsAreNotHosts() {
        assertNull(ReleaseIdentity.hostOf("1.0.0.127"))
        assertEquals("example.com", ReleaseIdentity.hostOf("com.example"))
    }

    @Test
    fun aSecondVersionTagDoesNotClaimThatVersion() {
        // version() reads the first `v`; covers() must agree, or this 1.0 release (by the trusted
        // key, earliest) would be picked for 2.0 and win that version's selection.
        val one = release(alice, "1.0", 100)
        val tags = one.tags.flatMap { if (it[0] == MagpieSpec.TAG_VERSION) listOf(it, arrayOf(MagpieSpec.TAG_VERSION, "2.0")) else listOf(it) }.toTypedArray()
        val both = alice.signed(EventTemplate<MavenReleaseEvent>(100, MagpieSpec.KIND_RELEASE, tags, "")) as MavenReleaseEvent
        val two = release(alice, "2.0", 200, seed = 'b')
        assertEquals("1.0", both.version())
        assertFalse(both.covers(module, "2.0"))
        val selected = WinnerSelection.release(listOf(both, two), module, "2.0", alice.pubKey)
        assertEquals(two.id, (selected as ReleaseSelection.Found).release.id)
    }
}
