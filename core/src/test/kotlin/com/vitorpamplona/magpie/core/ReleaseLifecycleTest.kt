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
import com.vitorpamplona.magpie.core.publish.MagpieSigner
import com.vitorpamplona.magpie.core.publish.PublishResult
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.core.resolve.RouterResponse
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.InMemoryBlossom
import com.vitorpamplona.magpie.testkit.TestBunker
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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

    /** Signs as [who], noting each request's kind and how many uploads the server had taken when it was sent. */
    private class WatchingSigner(
        who: TestIdentity,
        private val blossom: InMemoryBlossom,
    ) : MagpieSigner {
        private val real = SecretKeySigner(who.nsec)
        val seen: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
        override val signatures: Int get() = real.signatures

        override suspend fun pubKey(): HexKey = real.pubKey()

        override suspend fun sign(template: EventTemplate<*>): Event {
            seen += template.kind to blossom.uploads.get()
            return real.sign(template)
        }
    }

    private fun publishWith(
        signer: MagpieSigner,
        repo: File,
        version: String,
    ): PublishResult {
        val http = Http.client()
        val publisher = Publisher(QuartzNostrIO(http, world.log, idleTimeoutMs = 3_000), BlossomClient(http, world.log), signer, http, world.log)
        return runBlocking {
            publisher.publish(PublishSettings(repoDir = repo, group = "com.example", version = version, rootArtifact = "lib", indexRelays = listOf(world.relay.url)))
        }
    }

    @Test
    fun everySignatureIsRequestedBeforeTheFirstUpload() {
        // A person approving on a remote signer gets every request of a release together.
        world.setUpPublisher(alice)
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        val signer = WatchingSigner(alice, world.blossom)
        val result = publishWith(signer, repo.dir, "1.0.0")

        val authorizations = (result.uploaded + MAX_HASHES_PER_AUTH - 1) / MAX_HASHES_PER_AUTH
        // In any order: the requests go out together.
        assertEquals(
            (List(authorizations) { MagpieSpec.KIND_BLOSSOM_AUTH } + MagpieSpec.KIND_RELEASE).sorted(),
            signer.seen.map { it.first }.sorted(),
        )
        assertTrue(signer.seen.all { it.second == 0 }, "requested after uploads started: ${signer.seen}")
        assertEquals(result.uploaded, world.blossom.uploads.get())
        assertNotNull(result.eventId)
    }

    /** Holds each request for a moment and records how many were waiting on it at once. */
    private class QueueingSigner(
        who: TestIdentity,
    ) : MagpieSigner {
        private val real = SecretKeySigner(who.nsec)
        private val inFlight = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        override val signatures: Int get() = real.signatures

        override suspend fun pubKey(): HexKey = real.pubKey()

        override suspend fun sign(template: EventTemplate<*>): Event {
            mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                delay(300)
                return real.sign(template)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun aReleaseSendsEverySignatureRequestAtOnce() {
        // A remote signer queues them, so its user approves them in a row.
        world.setUpPublisher(alice)
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        val signer = QueueingSigner(alice)
        val result = publishWith(signer, repo.dir, "1.0.0")

        assertTrue(result.signatures > 2, "a release this size takes several authorizations: ${result.signatures}")
        assertEquals(result.signatures, signer.mostAtOnce.get(), "every request was waiting on the signer at once")
    }

    @Test
    fun aRateLimitedPreflightIsAskedAgainNotTakenAsARefusal() {
        // nostr.download, the release key's only server, may answer a burst of checks with 429.
        world.setUpPublisher(alice)
        world.blossom.busyPreflights.set(5)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val result = world.publish(alice, repo.dir, "1.0.0")
        assertNotNull(result.eventId)
        assertEquals(0, world.blossom.busyPreflights.get(), "the busy answers were used up")
    }

    @Test
    fun oneServerGetsAtMostEightRequestsAtOnce() {
        world.setUpPublisher(alice)
        world.blossom.answerDelayMs = 50
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        world.publish(alice, repo.dir, "1.0.0")
        // At most 8 per server; the server counts a request until its handler returns, a moment
        // after the client has its answer and sends the next, so allow a little overlap. Without
        // the cap, the 32 shared check slots all land on this one server.
        assertTrue(world.blossom.mostAtOnce.get() <= 12, "the server answered ${world.blossom.mostAtOnce.get()} at once")
    }

    @Test
    fun uploadsDoNotWaitForTheReleaseToBeApproved() {
        // The person approves the upload authorization at once and the release a while later:
        // the blobs upload meanwhile, and the release is published once both are done.
        world.setUpPublisher(alice)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val uploadsWhenReleaseApproved = AtomicInteger(-1)
        val signer =
            object : MagpieSigner {
                private val real = SecretKeySigner(alice.nsec)
                override val signatures: Int get() = real.signatures

                override suspend fun pubKey(): HexKey = real.pubKey()

                override suspend fun sign(template: EventTemplate<*>): Event {
                    if (template.kind == MagpieSpec.KIND_RELEASE) {
                        delay(1_500)
                        uploadsWhenReleaseApproved.set(world.blossom.uploads.get())
                    }
                    return real.sign(template)
                }
            }
        val result = publishWith(signer, repo.dir, "1.0.0")
        assertEquals(result.uploaded, uploadsWhenReleaseApproved.get(), "every blob was up before the release was approved")
        assertNotNull(result.eventId)
    }

    @Test
    fun aServerThatFailsItsUploadsGetsTheReleaseSignedAgainWithoutIt() {
        // Both servers pass the checks, so the early release names both; blossom2 then refuses
        // every upload, and the release that goes out must not send clients there.
        world.setUpPublisher(alice, servers = listOf(world.blossom.url, world.blossom2.url))
        world.blossom2.refuseAllUploads = true
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val signer = WatchingSigner(alice, world.blossom)
        val result = publishWith(signer, repo.dir, "1.0.0")

        val releases = signer.seen.filter { it.first == MagpieSpec.KIND_RELEASE }
        assertEquals(2, releases.size, "signed early with both servers, then again: ${signer.seen}")
        val published = MagpieEvents.wrap(world.relay.query(Filter(ids = listOf(result.eventId!!))).single()) as MavenReleaseEvent
        assertEquals(listOf(world.blossom.url), published.servers())
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

    private fun publishThroughBunker(
        bunker: TestBunker,
        repo: File,
        timeoutMs: Long = 20_000,
    ): PublishResult {
        val http = Http.client()
        val nostr = QuartzNostrIO(http, world.log, idleTimeoutMs = 3_000)
        try {
            BunkerSigner(bunker.uri, nostr, world.log, timeoutMs = timeoutMs).use { signer ->
                val publisher = Publisher(nostr, BlossomClient(http, world.log), signer, http, world.log)
                return runBlocking { publisher.publish(PublishSettings(repo, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
            }
        } finally {
            nostr.close()
        }
    }

    @Test
    fun aBunkerAnsweringOutOfOrderGetsEachUploadItsOwnAuthorization() {
        world.setUpPublisher(alice)
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        TestBunker(world.relay, alice).use { bunker ->
            // The first requests are answered last.
            bunker.approvalDelayMs = { n -> (5 - n).coerceAtLeast(0) * 400L }
            val result = publishThroughBunker(bunker, repo.dir)

            assertTrue(result.signatures > 2, "several authorizations and the release: ${result.signatures}")
            assertEquals(result.signatures, bunker.signRequests.get())
            // InMemoryBlossom only stores a blob whose authorization lists its hash.
            assertEquals(result.uploaded, world.blossom.uploads.get())
            assertNotNull(result.eventId)
        }
    }

    @Test
    fun aRequestWaitsWhileThePersonIsStillApprovingTheQueue() {
        // Four requests approved one at a time, 1.2 s apart: the last answer comes 4.8 s after
        // they were sent, past a 3 s window measured from sending, but never 3 s without an answer.
        // (The window also bounds the connect, so it leaves room for a loaded machine.)
        world.setUpPublisher(alice)
        val targets = (1..30).associate { "target$it" to "klib" }
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
        TestBunker(world.relay, alice).use { bunker ->
            bunker.oneApprovalAtATime = true
            bunker.approvalDelayMs = { 1_200 }
            val result = publishThroughBunker(bunker, repo.dir, timeoutMs = 3_000)

            assertTrue(result.signatures >= 4, "signatures: ${result.signatures}")
            assertNotNull(result.eventId)
        }
    }

    @Test
    fun aBunkerThatStopsAnsweringFailsThePublishInsteadOfHanging() {
        world.setUpPublisher(alice)
        TestBunker(world.relay, alice).use { bunker ->
            bunker.approvalDelayMs = { 60_000 }
            val started = System.currentTimeMillis()
            val error = assertFailsWith<IllegalStateException> { publishThroughBunker(bunker, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, timeoutMs = 1_500) }
            assertTrue("answered no request" in error.message!!, error.message)
            assertTrue(System.currentTimeMillis() - started < 15_000, "gave up within the window")
            assertEquals(0, world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size)
        }
    }

    @Test
    fun aDeclinedReSignPublishesTheReleaseSignedBeforeUploading() {
        // blossom2 passes the checks and then refuses its uploads, so the release is signed
        // again without it; the person declines that one, and the first release goes out.
        world.setUpPublisher(alice, servers = listOf(world.blossom.url, world.blossom2.url))
        world.blossom2.refuseAllUploads = true
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val signer =
            object : MagpieSigner {
                private val real = SecretKeySigner(alice.nsec)
                private val releases = AtomicInteger()
                override val signatures: Int get() = real.signatures

                override suspend fun pubKey(): HexKey = real.pubKey()

                override suspend fun sign(template: EventTemplate<*>): Event {
                    if (template.kind == MagpieSpec.KIND_RELEASE && releases.incrementAndGet() == 2) {
                        throw IllegalStateException("User has rejected the request.")
                    }
                    return real.sign(template)
                }
            }
        val result = publishWith(signer, repo.dir, "1.0.0")
        val published = MagpieEvents.wrap(world.relay.query(Filter(ids = listOf(result.eventId!!))).single()) as MavenReleaseEvent
        assertEquals(listOf(world.blossom.url, world.blossom2.url), published.servers(), "the early release, stale hint and all")
        world.stack(alice).use { assertEquals(200, it.get("com/example/lib/1.0.0/lib-1.0.0.module").status) }
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
