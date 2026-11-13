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
package com.vitorpamplona.magpie.core

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.MagpieSigner
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.publish.RepoScanner
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.InMemoryRelay
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** One test per audit finding, each failing on the behaviour it fixes. */
class AuditRegressionTest {
    private val world = MagpieWorld()
    private val alice = TestIdentity.ALICE

    @AfterTest
    fun tearDown() = world.close()

    private val deadRelay: String = "ws://127.0.0.1:${ServerSocket(0).use { it.localPort }}/"

    private fun settings(repo: FakeMavenRepo) = PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))

    @Test
    fun aRelaySendingForgedCopiesCannotHideTheGenuineEvent() {
        world.setUpPublisher(alice)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        world.publish(alice, repo.dir, "1.0.0")
        // A second relay holding the same events, which corrupts every signature and answers
        // first; the honest relay answers late.
        InMemoryRelay().use { hostile ->
            hostile.add(*world.relay.all().toTypedArray())
            hostile.transformOutgoing = { frame -> frame.replace(Regex("\"sig\":\"[0-9a-f]{8}"), "\"sig\":\"00000000") }
            world.relay.delayOutgoingMs = 300
            world.stack(alice, extra = mapOf(EngineConfig.RELAYS to "${hostile.url}\n${world.relay.url}")).use { stack ->
                assertContentEquals(
                    File(repo.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(),
                    stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes(),
                )
            }
        }
    }

    @Test
    fun aRelayOutageIsAnErrorNotAMissAndIsNotRemembered() {
        world.stack(alice, extra = mapOf(EngineConfig.RELAYS to deadRelay)).use { stack ->
            val path = "com/example/lib/1.0.0/lib-1.0.0.pom"
            assertEquals(502, stack.get(path).status)
            assertEquals(502, stack.get(path).status, "not turned into a cached 404")
            assertEquals(502, stack.get("com/example/lib/maven-metadata.xml").status)
        }
    }

    @Test
    fun publishingRefusesWhenNoWriteRelayCanConfirmTheVersionIsNew() {
        world.relay.add(
            alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_BLOSSOM_SERVERS, arrayOf(arrayOf("server", world.blossom.url)), "")),
            alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_RELAY_LIST, arrayOf(arrayOf("r", deadRelay)), "")),
        )
        val (publisher, signer) = world.publisher(alice)
        val error = assertFailsWith<IllegalStateException> { runBlocking { publisher.publish(settings(FakeMavenRepo(world.dir("repo")).publish("1.0.0"))) } }
        assertTrue("Can't confirm" in error.message!!, error.message)
        assertEquals(0, signer.signatures)
    }

    @Test
    fun aFrozenLockKeepsRefusingOnRetry() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0")
        world.stack(alice, extra = mapOf(EngineConfig.LOCK_FROZEN to "true")).use { stack ->
            val path = "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"
            assertEquals(500, stack.get(path).status)
            assertEquals(500, stack.get(path).status, "the retry must not be served from a cache the lock never approved")
        }
        // Same in a fresh daemon, where the disk cache now has the release.
        world.stack(alice, extra = mapOf(EngineConfig.LOCK_FROZEN to "true")).use { stack ->
            assertEquals(500, stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").status)
        }
    }

    @Test
    fun aServerRefusingEveryUploadCostsAtMostOneExtraSignature() {
        world.setUpPublisher(alice, listOf(world.blossom.url, world.blossom2.url))
        world.blossom2.refuseAllUploads = true
        val result = world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0")
        assertTrue(result.signatures <= 3, "signatures: ${result.signatures}")
        val release = MagpieEvents.wrap(world.relay.query(Filter(ids = listOf(result.eventId!!))).single()) as MagpieReleaseEvent
        assertEquals(listOf(world.blossom.url), release.servers(), "only the server that holds everything is a hint")
    }

    @Test
    fun aServerThatStoresSomethingElseDidNotStoreTheBlob() {
        world.setUpPublisher(alice)
        world.blossom.misreportHash = true
        val error = assertFailsWith<IllegalStateException> { world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0") }
        assertTrue("could not be uploaded" in error.message!!, error.message)
    }

    @Test
    fun closeHintsOnlyServersThatHoldEveryFile() {
        world.setUpPublisher(alice, listOf(world.blossom.url))
        val full = FakeMavenRepo(world.dir("full")).publish("2.0.0", targets = mapOf("jvm" to "jar", "linuxx64" to "klib"))
        val partials = world.dir("partials")
        world.publish(alice, full.dir, "2.0.0") { copy(partial = true, partialDir = partials, partialName = "all") }
        // A second server joins the list after the upload: it will only receive the manifest.
        world.relay.add(
            alice.sign(EventTemplate<Event>(2000, MagpieSpec.KIND_BLOSSOM_SERVERS, arrayOf(arrayOf("server", world.blossom.url), arrayOf("server", world.blossom2.url)), "")),
        )
        val (publisher, _) = world.publisher(alice)
        val result = runBlocking { publisher.close(PublishSettings(world.dir("x"), "com.example", "2.0.0", "lib", listOf(world.relay.url), partialDir = partials)) }
        val release = MagpieEvents.wrap(world.relay.query(Filter(ids = listOf(result.eventId!!))).single()) as MagpieReleaseEvent
        assertEquals(listOf(world.blossom.url), release.servers())
    }

    @Test
    fun theSignerMustSignWhatWasAskedWithTheExpectedKey() {
        world.setUpPublisher(alice)
        val impostor =
            object : MagpieSigner {
                private val real = SecretKeySigner(TestIdentity.MALLORY.nsec)
                override val signatures: Int get() = real.signatures

                override suspend fun pubKey(): HexKey = alice.pubKey

                override suspend fun sign(template: EventTemplate<*>): Event = real.sign(template)
            }
        val http = Http.client()
        val publisher = Publisher(QuartzNostrIO(http, world.log, 3_000), BlossomClient(http, world.log), impostor, http, world.log)
        val error = assertFailsWith<IllegalStateException> { runBlocking { publisher.publish(settings(FakeMavenRepo(world.dir("repo")).publish("1.0.0"))) } }
        assertTrue("doesn't match the request" in error.message!!, error.message)
        assertEquals(0, world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size)
    }

    @Test
    fun theNewestSnapshotBuildWinsByNumberNotByString() {
        val dir = world.dir("snap")
        FakeMavenRepo(dir).publish("3.0-SNAPSHOT", snapshotStamp = "20261113.101010-9", content = "nine")
        FakeMavenRepo(dir).publish("3.0-SNAPSHOT", snapshotStamp = "20261113.101010-10", content = "ten")
        val scanned = RepoScanner.scan(dir, "com.example", "3.0-SNAPSHOT", "lib")
        assertTrue(
            scanned.manifest.files.keys
                .all { "-9." !in it && "-9-" !in it },
            scanned.manifest.files.keys
                .toString(),
        )
        assertTrue(
            scanned.manifest.files.keys
                .any { "-10." in it },
        )
    }

    @Test
    fun partialDryRunsWriteNothing() {
        world.setUpPublisher(alice)
        val partials = world.dir("partials")
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0") {
            copy(partial = true, partialDir = partials, partialName = "a", dryRun = true)
        }
        assertEquals(0, partials.listFiles()!!.size)
        assertEquals(0, world.blossom.uploads.get())
    }
}
