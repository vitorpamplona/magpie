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

import com.vitorpamplona.magpie.core.publish.MissingServerListException
import com.vitorpamplona.magpie.core.publish.VersionAlreadyReleasedException
import com.vitorpamplona.magpie.core.resolve.RouterResponse
import com.vitorpamplona.magpie.core.util.Hashing
import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.ManifestFile
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublishResolveTest {
    private val world = MagpieWorld()
    private val alice = TestIdentity.ALICE
    private val mallory = TestIdentity.MALLORY

    @AfterTest
    fun tearDown() = world.close()

    private fun repo(
        version: String = "1.0.0",
        content: String = "v1",
    ): FakeMavenRepo = FakeMavenRepo(world.dir("repo-$version-$content")).publish(version, content = content)

    @Test
    fun publishTakesTwoSignaturesAndResolvesByteForByte() {
        world.setUpPublisher(alice)
        val repo = repo()
        val result = world.publish(alice, repo.dir, "1.0.0")
        assertEquals(2, result.signatures, "one upload authorization and one release")
        assertEquals(1, world.blossom.authEvents.size)
        assertNotNull(result.eventId)

        val releases = world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE)))
        assertEquals(1, releases.size)
        assertTrue(releases.single().toJson().length < MagpieSpec.MAX_EVENT_BYTES)

        world.stack(alice).use { stack ->
            listOf(
                "com/example/lib/1.0.0/lib-1.0.0.module",
                "com/example/lib/1.0.0/lib-1.0.0.pom",
                "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar",
                "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0-sources.jar",
                "com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib",
            ).forEach { path ->
                val local = File(repo.dir, path)
                val served = stack.get(path)
                assertContentEquals(local.readBytes(), served.bytes(), path)
                ChecksumAlgorithm.entries.forEach { algorithm ->
                    assertEquals(
                        Hashing.checksum(local, algorithm),
                        stack.get("$path.${algorithm.extension}").text(),
                        "$path.${algorithm.extension}",
                    )
                }
            }
            // Gradle's own sidecars were not published as files.
            assertIs<RouterResponse.Error>(stack.get("com/example/lib/1.0.0/lib-1.0.0.pom.sha1.sha1"))

            val metadata = stack.get("com/example/lib-jvm/maven-metadata.xml").text()
            assertTrue("<release>1.0.0</release>" in metadata, metadata)
            assertTrue("<version>1.0.0</version>" in metadata)
        }

        // Every module coordinate that resolved is pinned in the lock.
        val lock = LockFile.parse(File(world.root, "magpie.lock").readText())
        assertEquals(result.manifestSha256, lock.releaseFor(Coordinate("com.example", "lib-jvm", "1.0.0"))?.manifestSha256)
        assertEquals(result.eventId, lock.releaseFor(Coordinate("com.example", "lib-jvm", "1.0.0"))?.eventId)
        assertEquals(result.manifestSha256, lock.releaseFor(Coordinate("com.example", "lib", "1.0.0"))?.manifestSha256)
    }

    @Test
    fun secondPublishReusesBlobsAndUploadsNothing() {
        world.setUpPublisher(alice)
        val first = world.publish(alice, repo("1.0.0").dir, "1.0.0")
        val uploads = world.blossom.uploads.get()
        assertTrue(first.uploaded > 0)
        // Same bytes under a new version: only files whose bytes changed (POMs, modules) upload.
        val second = world.publish(alice, repo("1.0.1").dir, "1.0.1")
        assertTrue(second.uploaded < first.uploaded, "uploaded ${second.uploaded} vs ${first.uploaded}")
        assertTrue(world.blossom.uploads.get() - uploads == second.uploaded)
    }

    @Test
    fun refusesToOverwriteAVersion() {
        world.setUpPublisher(alice)
        world.publish(alice, repo("1.0.0", "v1").dir, "1.0.0")
        val (publisher, signer) = world.publisher(alice)
        val error =
            assertFailsWith<VersionAlreadyReleasedException> {
                kotlinx.coroutines.runBlocking {
                    publisher.publish(
                        com.vitorpamplona.magpie.core.publish.PublishSettings(
                            repoDir = repo("1.0.0", "v2").dir,
                            group = "com.example",
                            version = "1.0.0",
                            rootArtifact = "lib",
                            indexerRelays = listOf(world.relay.url),
                        ),
                    )
                }
            }
        assertTrue("immutable" in error.message!!)
        assertEquals(0, signer.signatures, "nothing signed")
    }

    @Test
    fun earliestReleaseWinsEvenIfALaterOneIsPublished() {
        world.setUpPublisher(alice)
        val repoV1 = repo("1.0.0", "v1")
        world.publish(alice, repoV1.dir, "1.0.0")
        // A second release of the same version, sneaked onto the relay later (bypassing the check).
        val other = repo("1.0.0", "evil")
        val manifest =
            com.vitorpamplona.magpie.core.publish.RepoScanner
                .scan(other.dir, "com.example", "1.0.0", "lib")
                .manifest
        world.relay.add(alice.sign(MagpieReleaseEvent.build(manifest, createdAt = System.currentTimeMillis() / 1000 + 100)))

        world.stack(alice).use { stack ->
            val jar = stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes()
            assertContentEquals(File(repoV1.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(), jar)
        }
    }

    @Test
    fun onlyThePinnedKeyCounts() {
        world.setUpPublisher(alice)
        world.setUpPublisher(mallory)
        world.publish(mallory, repo("6.6.6").dir, "6.6.6")
        world.stack(alice).use { stack ->
            assertEquals(404, stack.get("com/example/lib/6.6.6/lib-6.6.6.pom").status)
            assertEquals(404, stack.get("com/example/lib/maven-metadata.xml").status)
            assertEquals(404, stack.get("org/other/lib/1.0/lib-1.0.pom").status, "unconfigured group")
        }
    }

    @Test
    fun lockPinsTheReleaseAgainstABackdatedOne() {
        world.setUpPublisher(alice)
        val repoV1 = repo("1.0.0", "v1")
        val published = world.publish(alice, repoV1.dir, "1.0.0")
        val lockFile = File(world.root, "magpie.lock")
        world.stack(alice, cache = world.dir("cache-a")).use { it.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes() }
        assertEquals(published.manifestSha256, LockFile.parse(lockFile.readText()).releaseFor(Coordinate("com.example", "lib-jvm", "1.0.0"))?.manifestSha256)

        // The key signs an earlier-dated release (a compromised key could). Without the lock it
        // would win; with it, resolution keeps the locked one.
        val evil = repo("1.0.0", "evil")
        val manifest =
            com.vitorpamplona.magpie.core.publish.RepoScanner
                .scan(evil.dir, "com.example", "1.0.0", "lib")
                .manifest
        evil.dir
            .walk()
            .filter { it.isFile }
            .forEach { world.blossom.put(it.readBytes()) }
        world.blossom.put(manifest.toBytes())
        world.relay.add(alice.sign(MagpieReleaseEvent.build(manifest, servers = listOf(world.blossom.url), createdAt = 10)))

        world.stack(alice, cache = world.dir("cache-b")).use { stack ->
            assertContentEquals(
                File(repoV1.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(),
                stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes(),
            )
        }
        world.stack(alice, cache = world.dir("cache-c"), lock = null).use { stack ->
            assertContentEquals(
                File(evil.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(),
                stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").bytes(),
                "without a lock, the earliest wins",
            )
        }

        // A lock pinning an id the relays don't have fails loudly.
        lockFile.writeText(LockFile().withRelease(Coordinate("com.example", "lib-jvm", "1.0.0"), "f".repeat(64)).render())
        world.stack(alice, cache = world.dir("cache-d")).use { stack ->
            val error = assertIs<RouterResponse.Error>(stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"))
            assertEquals(500, error.status)
            assertTrue(world.log.lines.any { it.startsWith("ERROR") && "pins com.example:lib-jvm:1.0.0" in it })
        }
    }

    @Test
    fun offlineServesFromTheCache() {
        world.setUpPublisher(alice)
        val repo = repo()
        world.publish(alice, repo.dir, "1.0.0")
        val cache = world.dir("cache")
        val path = "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"
        world.stack(alice, cache).use { it.get(path).bytes() }
        world.stack(alice, cache).use { it.get("com/example/lib-jvm/maven-metadata.xml").bytes() }

        world.relay.close()
        world.blossom.available = false
        world.stack(alice, cache, offline = true).use { stack ->
            assertContentEquals(File(repo.dir, path).readBytes(), stack.get(path).bytes())
            assertTrue("1.0.0" in stack.get("com/example/lib-jvm/maven-metadata.xml").text())
            assertEquals(404, stack.get("com/example/lib-jvm/9.9.9/lib-jvm-9.9.9.jar").status)
        }
        // The mirror holds a plain Maven layout of what was served, for Gradle's --offline.
        assertTrue(File(cache, "mirror/${alice.pubKey}/$path").isFile)
    }

    @Test
    fun blobsComeFromTheServerListWhenHintsFailAndFromFallbacksLast() {
        world.setUpPublisher(alice, listOf(world.blossom.url))
        val repo = repo()
        world.publish(alice, repo.dir, "1.0.0")
        // The release's hint server dies; the publisher moved to a new server and copied blobs.
        world.blossom.blobs.forEach { (sha, bytes) -> world.blossom2.blobs[sha] = bytes }
        world.blossom.available = false
        world.relay.add(
            alice.sign(
                com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<com.vitorpamplona.quartz.nip01Core.core.Event>(
                    2000,
                    MagpieSpec.KIND_BLOSSOM_SERVERS,
                    arrayOf(arrayOf("server", world.blossom2.url)),
                    "",
                ),
            ),
        )
        world.stack(alice).use { stack ->
            assertContentEquals(
                File(repo.dir, "com/example/lib/1.0.0/lib-1.0.0.pom").readBytes(),
                stack.get("com/example/lib/1.0.0/lib-1.0.0.pom").bytes(),
            )
        }
        // A third run with neither, but a fallback mirror.
        world.blossom2.available = false
        val fallback =
            com.vitorpamplona.magpie.testkit
                .InMemoryBlossom()
        world.blossom2.blobs.forEach { (sha, bytes) -> fallback.blobs[sha] = bytes }
        fallback.use {
            world
                .stack(
                    alice,
                    cache = world.dir("cache2"),
                    extra =
                        mapOf(com.vitorpamplona.magpie.bridge.EngineConfig.FALLBACK_SERVERS to it.url),
                ).use { stack ->
                    assertEquals(200, stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").status)
                }
        }
    }

    @Test
    fun aServerServingWrongBytesIsRejected() {
        world.setUpPublisher(alice)
        val repo = repo()
        world.publish(alice, repo.dir, "1.0.0")
        val sha = Hashing.sha256(File(repo.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"))
        world.blossom.blobs[sha] = "tampered".toByteArray()
        world.stack(alice).use { stack ->
            val response = stack.get("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar")
            assertEquals(502, response.status)
        }
    }

    @Test
    fun missingServerListFailsWithAHint() {
        val error = assertFailsWith<MissingServerListException> { world.publish(alice, repo().dir, "1.0.0") }
        assertTrue("createNostrServerList" in error.message!!)
    }

    @Test
    fun serverListTaskPublishesKind10063() {
        val (publisher, signer) = world.publisher(alice)
        kotlinx.coroutines.runBlocking { publisher.createServerList(listOf(world.blossom.url), listOf(world.relay.url)) }
        assertEquals(1, signer.signatures)
        world.publish(alice, repo().dir, "1.0.0")
    }

    @Test
    fun manifestsAreParsedFromBlossom() {
        world.setUpPublisher(alice)
        val result = world.publish(alice, repo().dir, "1.0.0")
        val manifest = Manifest.parse(world.blossom.blobs.getValue(result.manifestSha256))
        assertEquals("com.example:lib:1.0.0", manifest.coordinate.toString())
        assertEquals(listOf("lib", "lib-jvm", "lib-linuxx64"), manifest.artifacts)
        assertTrue(manifest.files.values.all { it is ManifestFile })
    }
}
