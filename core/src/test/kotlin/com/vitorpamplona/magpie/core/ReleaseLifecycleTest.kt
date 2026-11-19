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

import com.sun.net.httpserver.HttpServer
import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.BunkerSigner
import com.vitorpamplona.magpie.core.publish.MAX_HASHES_PER_AUTH
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.resolve.RouterResponse
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.TestBunker
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Multi-host releases, BUD-06, auth fallback, snapshots, NIP-05 trust and NIP-46. */
class ReleaseLifecycleTest {
    private val world = MagpieWorld()
    private val alice = TestIdentity.ALICE

    @AfterTest
    fun tearDown() = world.close()

    @Test
    fun partialPublishesFromTwoHostsCloseIntoOneRelease() {
        world.setUpPublisher(alice)
        val full = FakeMavenRepo(world.dir("full")).publish("2.0.0", targets = mapOf("jvm" to "jar", "linuxx64" to "klib", "iosarm64" to "klib"))
        val linux = full.copyOf(world.dir("linux-host"), setOf("lib", "lib-jvm", "lib-linuxx64"))
        val mac = full.copyOf(world.dir("mac-host"), setOf("lib", "lib-iosarm64"))
        val partials = world.dir("partials")

        val linuxResult = world.publish(alice, linux.dir, "2.0.0") { copy(partial = true, partialDir = partials, partialName = "linux") }
        val macResult = world.publish(alice, mac.dir, "2.0.0") { copy(partial = true, partialDir = partials, partialName = "macos") }
        assertEquals(null, linuxResult.eventId)
        assertEquals(null, macResult.eventId)
        assertTrue(world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).isEmpty(), "partials sign no release")

        val (publisher, signer) = world.publisher(alice)
        val closed =
            runBlocking {
                publisher.close(PublishSettings(world.dir("unused"), "com.example", "2.0.0", "lib", listOf(world.relay.url), partialDir = partials))
            }
        assertNotNull(closed.eventId)
        assertEquals(2, signer.signatures, "close signs the manifest upload and the release")

        world.stack(alice).use { stack ->
            listOf("lib-jvm/2.0.0/lib-jvm-2.0.0.jar", "lib-linuxx64/2.0.0/lib-linuxx64-2.0.0.klib", "lib-iosarm64/2.0.0/lib-iosarm64-2.0.0.klib").forEach {
                assertContentEquals(File(full.dir, "com/example/$it").readBytes(), stack.get("com/example/$it").bytes(), it)
            }
        }
    }

    @Test
    fun closeRefusesWhenAHostIsMissing() {
        world.setUpPublisher(alice)
        val full = FakeMavenRepo(world.dir("full")).publish("2.0.0", targets = mapOf("jvm" to "jar", "iosarm64" to "klib"))
        val linux = full.copyOf(world.dir("linux-host"), setOf("lib", "lib-jvm"))
        val partials = world.dir("partials")
        world.publish(alice, linux.dir, "2.0.0") { copy(partial = true, partialDir = partials, partialName = "linux") }

        val (publisher, signer) = world.publisher(alice)
        val error =
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    publisher.close(PublishSettings(world.dir("unused"), "com.example", "2.0.0", "lib", listOf(world.relay.url), partialDir = partials))
                }
            }
        assertTrue("lib-iosarm64/2.0.0/lib-iosarm64-2.0.0.module" in error.message!!, error.message)
        assertEquals(0, signer.signatures)
    }

    @Test
    fun singleHostPublishRefusesIncompleteModuleMetadata() {
        world.setUpPublisher(alice)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", skipTargets = setOf("linuxx64"))
        val error = assertFailsWith<IllegalStateException> { world.publish(alice, repo.dir, "1.0.0") }
        assertTrue("lib-linuxx64" in error.message!!)
    }

    @Test
    fun preflightFailsBeforeAnySignature() {
        world.setUpPublisher(alice)
        world.blossom.maxUploadSize = 100
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val (publisher, signer) = world.publisher(alice)
        val error =
            assertFailsWith<IllegalStateException> {
                runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
            }
        assertTrue("No Blossom server can host" in error.message!!, error.message)
        assertEquals(0, signer.signatures)
        assertEquals(0, world.blossom.uploads.get())
        assertTrue(world.blossom.preflights.get() > 0)
    }

    @Test
    fun aServerThatWantsAuthorizationForThePreflightStillGetsTheUpload() {
        // blossom.band answers an unsigned HEAD /upload with 401. That isn't a refusal: Magpie
        // signs nothing before its checks pass, so the signed upload decides.
        world.setUpPublisher(alice)
        world.blossom.preflightNeedsAuth = true
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val (publisher, signer) = world.publisher(alice)
        runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
        assertTrue(world.blossom.preflights.get() > 0)
        assertTrue(world.blossom.uploads.get() > 0)
        assertEquals(2, signer.signatures)
    }

    @Test
    fun preflightLetsASecondServerHostWhatTheFirstRefuses() {
        world.setUpPublisher(alice, listOf(world.blossom.url, world.blossom2.url))
        world.blossom.maxUploadSize = 100
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val result = world.publish(alice, repo.dir, "1.0.0")
        assertEquals(2, result.signatures)
        world.stack(alice).use { assertEquals(200, it.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").status) }
    }

    @Test
    fun serversThatRefuseMultiHashAuthGetOnePerBlob() {
        world.setUpPublisher(alice)
        world.blossom.rejectMultiHashAuth = true
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val result = world.publish(alice, repo.dir, "1.0.0")
        assertTrue(result.signatures > 2, "fell back to per-blob auth: ${result.signatures}")
        assertEquals(result.uploaded, world.blossom.uploads.get())
        world.stack(alice).use { assertEquals(200, it.get("com/example/lib/1.0.0/lib-1.0.0.module").status) }
    }

    @Test
    fun largeReleasesSplitTheUploadAuthorizationUnderHeaderLimits() {
        // nostr.download, like most Blossom servers, sits behind nginx: an Authorization header
        // over 8 KB (about 78 hashes in one authorization) gets 400 Request Header Or Cookie Too Large.
        world.setUpPublisher(alice)
        world.blossom.maxAuthHeaderBytes = 8 * 1024
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        val result = world.publish(alice, repo.dir, "1.0.0")

        assertTrue(result.uploaded > 100, "a release this size used to need one ${result.uploaded}-hash authorization")
        val authorizations = (result.uploaded + MAX_HASHES_PER_AUTH - 1) / MAX_HASHES_PER_AUTH
        assertEquals(authorizations + 1, result.signatures, "one authorization per $MAX_HASHES_PER_AUTH hashes, and the release")
        assertEquals(authorizations, world.blossom.authEvents.size)
        world.blossom.authEvents.values.forEach { auth ->
            assertTrue(auth.tags.count { it[0] == "x" } <= MAX_HASHES_PER_AUTH)
        }
        assertEquals(result.uploaded, world.blossom.uploads.get())
        world.stack(alice).use { assertEquals(200, it.get("com/example/lib-target30/1.0.0/lib-target30-1.0.0.klib").status) }
    }

    @Test
    fun snapshotsResolveThroughTimestampedMetadataAndMoveForward() {
        world.setUpPublisher(alice)
        val v = "3.0-SNAPSHOT"
        val build1 = FakeMavenRepo(world.dir("snap1")).publish(v, snapshotStamp = "20261113.101010-1", content = "build1")
        world.publish(alice, build1.dir, v)
        world.stack(alice, cache = world.dir("cache1")).use { stack ->
            val metadata = stack.get("com/example/lib-jvm/$v/maven-metadata.xml").text()
            assertTrue("<timestamp>20261113.101010</timestamp>" in metadata, metadata)
            assertTrue("<buildNumber>1</buildNumber>" in metadata)
            assertTrue("<value>3.0-20261113.101010-1</value>" in metadata)
            val stamped = "com/example/lib-jvm/$v/lib-jvm-3.0-20261113.101010-1.jar"
            assertContentEquals(File(build1.dir, stamped).readBytes(), stack.get(stamped).bytes())
            // The plain -SNAPSHOT name maps to the newest build.
            assertContentEquals(File(build1.dir, stamped).readBytes(), stack.get("com/example/lib-jvm/$v/lib-jvm-$v.jar").bytes())
            assertTrue("<version>$v</version>" in stack.get("com/example/lib-jvm/maven-metadata.xml").text())
        }

        Thread.sleep(1100) // a newer created_at
        val build2 = FakeMavenRepo(world.dir("snap2")).publish(v, snapshotStamp = "20261113.111111-2", content = "build2")
        world.publish(alice, build2.dir, v)
        world.stack(alice, cache = world.dir("cache2")).use { stack ->
            val stamped = "com/example/lib-jvm/$v/lib-jvm-3.0-20261113.111111-2.jar"
            assertTrue("<buildNumber>2</buildNumber>" in stack.get("com/example/lib-jvm/$v/maven-metadata.xml").text())
            assertContentEquals(File(build2.dir, stamped).readBytes(), stack.get("com/example/lib-jvm/$v/lib-jvm-$v.jar").bytes())
        }
    }

    @Test
    fun nip05IsTrustedOnFirstUseAndAKeyChangeFailsLoudly() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("r1")).publish("1.0.0").dir, "1.0.0")
        var served = alice.pubKey
        val wellKnown =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/.well-known/nostr.json") { ex ->
                    val body = """{"names":{"alice":"$served"}}""".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val extra = mapOf("nip05BaseUrl" to "http://127.0.0.1:${wellKnown.address.port}")
            val lock = File(world.root, "magpie.lock")
            world.stack(alice, pin = "nip05:alice@example.com", extra = extra).use { stack ->
                assertEquals(200, stack.get("com/example/lib/1.0.0/lib-1.0.0.pom").status)
            }
            val locked = LockFile.parse(lock.readText()).publishers.getValue("com.example")
            assertEquals(alice.pubKey, locked.pubKey)
            assertEquals("nip05:alice@example.com", locked.source)

            // The name now points at another key: the next build refuses, loudly.
            served = TestIdentity.MALLORY.pubKey
            world.stack(alice, cache = world.dir("cache2"), pin = "nip05:alice@example.com", extra = extra).use { stack ->
                val response = assertIs<RouterResponse.Error>(stack.get("com/example/lib/1.0.0/lib-1.0.0.pom"))
                assertEquals(500, response.status)
                assertTrue("PUBLISHER KEY CHANGED" in response.message, response.message)
            }

            // Offline, the lock alone decides.
            world.stack(alice, pin = "nip05:alice@example.com", extra = extra, offline = true).use { stack ->
                assertEquals(200, stack.get("com/example/lib/1.0.0/lib-1.0.0.pom").status)
            }
        } finally {
            wellKnown.stop(0)
        }
    }

    @Test
    fun frozenLockRefusesNewEntries() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("r1")).publish("1.0.0").dir, "1.0.0")
        world.stack(alice, extra = mapOf(EngineConfig.LOCK_FROZEN to "true")).use { stack ->
            assertEquals(500, stack.get("com/example/lib/1.0.0/lib-1.0.0.pom").status)
        }
        assertFalse(File(world.root, "magpie.lock").exists())
    }

    @Test
    fun nip46BunkerSignsTheRelease() {
        world.setUpPublisher(alice)
        TestBunker(world.relay, alice).use { bunker ->
            val http = Http.client()
            val nostr = QuartzNostrIO(http, world.log, idleTimeoutMs = 3_000)
            BunkerSigner(bunker.uri, nostr, world.log, timeoutMs = 20_000).use { signer ->
                val publisher = Publisher(nostr, BlossomClient(http, world.log), signer, http, world.log)
                val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
                val result = runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
                assertEquals(2, result.signatures)
                assertEquals(2, bunker.signRequests.get())
                val release = world.relay.query(Filter(ids = listOf(result.eventId!!))).single()
                assertEquals(alice.pubKey, release.pubKey)
            }
            nostr.close()
        }
    }
}
