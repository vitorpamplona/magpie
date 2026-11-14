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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            world.stack(alice, extra = mapOf(EngineConfig.INDEX_RELAYS to "${hostile.url}\n${world.relay.url}")).use { stack ->
                assertContentEquals(
                    File(repo.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(),
                    stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes(),
                )
            }
        }
    }

    @Test
    fun aRelayOutageIsAnErrorNotAMissAndIsNotRemembered() {
        world.stack(alice, extra = mapOf(EngineConfig.INDEX_RELAYS to deadRelay)).use { stack ->
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

    // ---- Resolver hardening ----

    @Test
    fun aMalformedServerUrlDoesNotStopTheFallbackChain() {
        world.setUpPublisher(alice)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        val scanned = RepoScanner.scan(repo.dir, "com.example", "1.0.0", "lib")
        scanned.files.values.forEach { world.blossom.put(it.readBytes()) }
        world.blossom.put(scanned.manifest.toBytes())
        world.relay.add(alice.sign(MagpieReleaseEvent.build(scanned.manifest, servers = listOf("blossom.example.com", world.blossom.url), createdAt = 100)))
        world.stack(alice).use { assertEquals(200, it.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").status) }
    }

    @Test
    fun concurrentRequestsForOneBlobDownloadItOnce() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0")
        val before = world.blossom.downloads.get()
        world.stack(alice).use { stack ->
            runBlocking {
                (1..12)
                    .map { async(Dispatchers.IO) { stack.router.handle("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar") } }
                    .forEach { assertEquals(200, it.await().status) }
            }
        }
        // The jar once, the manifest once, and the module's .pom/.module once each for the mirror.
        assertTrue(world.blossom.downloads.get() - before <= 4, "downloads: ${world.blossom.downloads.get() - before}")
    }

    @Test
    fun headAndSha256AnswersDoNotDownloadTheFile() {
        world.setUpPublisher(alice)
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
        world.publish(alice, repo.dir, "1.0.0")
        // Without the offline mirror (as in the gateway): with it, the first request for a file
        // downloads it once so the mirror has it.
        world.stack(alice, extra = mapOf(com.vitorpamplona.magpie.core.engine.ResolverStack.MIRROR to "false")).use { stack ->
            val path = "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"
            stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.pom") // fetches the manifest
            val before = world.blossom.downloads.get()
            val head = runBlocking { stack.router.handle(path, head = true) }
            assertEquals(File(repo.dir, path).length(), (head as com.vitorpamplona.magpie.core.resolve.RouterResponse.Head).length)
            assertEquals(
                com.vitorpamplona.magpie.core.util.Hashing
                    .sha256(File(repo.dir, path)),
                stack.get("$path.sha256").text(),
            )
            assertEquals(before, world.blossom.downloads.get())
            assertTrue("immutable" in head.headers.getValue("Cache-Control"))
        }
    }

    @Test
    fun floatingSnapshotNamesAreNotCachedForever() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("snap")).publish("3.0-SNAPSHOT", snapshotStamp = "20261113.101010-1").dir, "3.0-SNAPSHOT")
        world.stack(alice).use { stack ->
            val floating = stack.get("com/example/lib-jvm/3.0-SNAPSHOT/lib-jvm-3.0-SNAPSHOT.jar")
            assertEquals("no-cache", floating.headers["Cache-Control"])
            val stamped = stack.get("com/example/lib-jvm/3.0-SNAPSHOT/lib-jvm-3.0-20261113.101010-1.jar")
            assertTrue("immutable" in stamped.headers.getValue("Cache-Control"))
        }
    }

    @Test
    fun percentEncodedPathsResolve() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0+build").dir, "1.0+build")
        world.stack(alice).use { stack ->
            assertEquals(200, stack.get("com/example/lib-jvm/1.0%2Bbuild/lib-jvm-1.0%2Bbuild.jar").status)
            assertEquals(200, stack.get("com/example/lib-jvm/1.0+build/lib-jvm-1.0+build.jar").status)
        }
    }

    @Test
    fun oneProjectsLockDoesNotDecideForAnother() {
        world.setUpPublisher(alice)
        val first = FakeMavenRepo(world.dir("r1")).publish("1.0.0", content = "first")
        world.publish(alice, first.dir, "1.0.0")
        val later = FakeMavenRepo(world.dir("r2")).publish("1.0.0", content = "later")
        val laterManifest = RepoScanner.scan(later.dir, "com.example", "1.0.0", "lib").manifest
        later.dir
            .walk()
            .filter { it.isFile }
            .forEach { world.blossom.put(it.readBytes()) }
        world.blossom.put(laterManifest.toBytes())
        world.relay.add(alice.sign(MagpieReleaseEvent.build(laterManifest, servers = listOf(world.blossom.url), createdAt = System.currentTimeMillis() / 1000 + 100)))

        val cache = world.dir("shared-cache")
        val pinsLater = File(world.root, "a.lock")
        pinsLater.writeText(
            com.vitorpamplona.magpie.spec
                .LockFile()
                .withRelease(
                    com.vitorpamplona.magpie.spec
                        .Coordinate("com.example", "lib-jvm", "1.0.0"),
                    laterManifest.sha256Hex(),
                ).render(),
        )
        val path = "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"
        world.stack(alice, cache = cache, lock = pinsLater).use { assertContentEquals(File(later.dir, path).readBytes(), it.get(path).bytes()) }
        world.stack(alice, cache = cache, lock = File(world.root, "b.lock")).use {
            assertContentEquals(File(first.dir, path).readBytes(), it.get(path).bytes(), "no lock: the earliest, not project A's pick")
        }
    }

    @Test
    fun siblingModulesOfAMultiplatformReleaseShareOneRelayQuery() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0")
        world.stack(alice).use { stack ->
            stack.get("com/example/lib/1.0.0/lib-1.0.0.module").bytes()
            val reqs = world.relay.reqCount.get()
            stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.module").bytes()
            stack.get("com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module").bytes()
            assertEquals(reqs, world.relay.reqCount.get(), "no new release queries for sibling modules")
        }
        val lock =
            com.vitorpamplona.magpie.spec.LockFile
                .parse(File(world.root, "magpie.lock").readText())
        assertTrue(
            lock.releaseFor(
                com.vitorpamplona.magpie.spec
                    .Coordinate("com.example", "lib-jvm", "1.0.0"),
            ) != null,
            "siblings are recorded when used",
        )
    }

    @Test
    fun theLocalServerOnlyAnswersUnderItsPrefix() {
        world.setUpPublisher(alice)
        world.publish(alice, FakeMavenRepo(world.dir("repo")).publish("1.0.0").dir, "1.0.0")
        world.stack(alice).use { stack ->
            com.vitorpamplona.magpie.core.resolve
                .LocalRepoServer(stack.router, world.log, 0, "abc123")
                .use { server ->
                    val http = Http.client()

                    fun status(url: String) =
                        http
                            .newCall(
                                okhttp3.Request
                                    .Builder()
                                    .url(url)
                                    .build(),
                            ).execute()
                            .use { it.code }
                    assertEquals(200, status(server.url + "com/example/lib/1.0.0/lib-1.0.0.pom"))
                    assertEquals(404, status("http://127.0.0.1:${server.port}/com/example/lib/1.0.0/lib-1.0.0.pom"))
                }
        }
    }
}
