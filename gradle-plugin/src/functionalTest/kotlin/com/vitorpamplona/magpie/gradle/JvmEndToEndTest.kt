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
package com.vitorpamplona.magpie.gradle

import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmEndToEndTest {
    private val builds = Builds()

    @AfterTest
    fun tearDown() = builds.close()

    @Test
    fun publishThenConsumeThroughTheSettingsPlugin() {
        val lib = builds.javaLibrary("lib", "1.0.0")
        val published = builds.run(lib, "publishToNostr", env = builds.signerEnv)
        assertEquals(TaskOutcome.SUCCESS, published.task(":publishToNostr")?.outcome)
        val releases = builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE)))
        assertEquals(1, releases.size)
        assertEquals(1, builds.blossom.authEvents.size, "one upload authorization")

        val app = builds.consumer("app", "com.example:greeter:1.0.0")
        val result = builds.run(app, "run", "--configuration-cache")
        assertTrue("GREETING: hello from nostr" in result.output)

        val lock = LockFile.parse(File(app, "gradle/magpie.lock").readText())
        assertEquals(releases.single().id, lock.releases["com.example:greeter:1.0.0"])

        // Configuration cache reuse: a new build, a new server on a new port, same result. (The
        // second run stores again: the first created the engine directory under the cache.)
        builds.run(app, "run", "--configuration-cache")
        val again = builds.run(app, "run", "--configuration-cache", "--rerun-tasks")
        assertTrue("Reusing configuration cache" in again.output || "Configuration cache entry reused" in again.output, again.output)
        assertTrue("GREETING: hello from nostr" in again.output)
    }

    @Test
    fun offlineBuildResolvesFromTheCache() {
        builds.run(builds.javaLibrary("lib", "1.0.0"), "publishToNostr", env = builds.signerEnv)
        val app = builds.consumer("app", "com.example:greeter:1.0.0")
        builds.run(app, "run")
        builds.relay.close()
        builds.blossom.available = false
        val offline = builds.run(app, "run", "--offline", "--refresh-dependencies")
        assertTrue("GREETING: hello from nostr" in offline.output, offline.output)
    }

    @Test
    fun refusesToOverwriteAVersion() {
        builds.run(builds.javaLibrary("lib", "1.0.0"), "publishToNostr", env = builds.signerEnv)
        val again = builds.fail(builds.javaLibrary("lib2", "1.0.0", greeting = "a different 1.0.0"), "publishToNostr", env = builds.signerEnv)
        assertTrue("already released" in again.output && "immutable" in again.output, again.output)
        assertEquals(1, builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size)
    }

    @Test
    fun aLockFileMismatchFailsTheBuild() {
        builds.run(builds.javaLibrary("lib", "1.0.0"), "publishToNostr", env = builds.signerEnv)
        val app = builds.consumer("app", "com.example:greeter:1.0.0")
        File(app, "gradle").mkdirs()
        File(app, "gradle/magpie.lock").writeText(
            LockFile()
                .withRelease(
                    com.vitorpamplona.magpie.spec
                        .Coordinate("com.example", "greeter", "1.0.0"),
                    "e".repeat(64),
                ).render(),
        )
        val result = builds.fail(app, "run")
        assertTrue("magpie.lock pins com.example:greeter:1.0.0" in result.output, result.output)
    }

    @Test
    fun yankedVersionsStillResolve() {
        builds.run(builds.javaLibrary("lib", "1.0.0"), "publishToNostr", env = builds.signerEnv)
        val release = builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).single()
        builds.run(builds.dir("lib"), "yankNostrRelease", "--release=${release.id}", "--reason=CVE-2026-0002", env = builds.signerEnv)
        assertEquals(1, builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_YANK))).size)

        val result = builds.run(builds.consumer("app", "com.example:greeter:1.0.0"), "run")
        assertTrue("GREETING: hello from nostr" in result.output)
        assertTrue("yanked" in result.output && "CVE-2026-0002" in result.output, result.output)
    }

    @Test
    fun dynamicVersionsUseGeneratedMetadata() {
        builds.run(builds.javaLibrary("lib1", "1.0.0", greeting = "one"), "publishToNostr", env = builds.signerEnv)
        Thread.sleep(1100)
        builds.run(builds.javaLibrary("lib2", "1.1.0", greeting = "one point one"), "publishToNostr", env = builds.signerEnv)
        val result = builds.run(builds.consumer("app", "com.example:greeter:1.+"), "run")
        assertTrue("GREETING: one point one" in result.output, result.output)
    }

    @Test
    fun snapshotsResolveAndMoveForward() {
        builds.run(builds.javaLibrary("snap1", "2.0-SNAPSHOT", greeting = "snapshot one"), "publishToNostr", env = builds.signerEnv)
        val app = builds.consumer("app", "com.example:greeter:2.0-SNAPSHOT")
        assertTrue("GREETING: snapshot one" in builds.run(app, "run").output)

        Thread.sleep(1100) // a newer created_at replaces the addressable event
        builds.run(builds.javaLibrary("snap2", "2.0-SNAPSHOT", greeting = "snapshot two"), "publishToNostr", env = builds.signerEnv)
        val second = builds.run(app, "run", "--refresh-dependencies")
        assertTrue("GREETING: snapshot two" in second.output, second.output)
        assertEquals(0, builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size, "snapshots are not releases")
    }

    @Test
    fun missingServerListOffersTheTask() {
        val lib = builds.javaLibrary("lib", "1.0.0")
        val stranger =
            com.vitorpamplona.magpie.testkit
                .TestIdentity()
        val result = builds.fail(lib, "publishToNostr", env = mapOf("MAGPIE_NSEC" to stranger.nsec))
        assertTrue("createNostrServerList" in result.output, result.output)
        builds.run(lib, "createNostrServerList", "--server=${builds.blossom.url}", env = mapOf("MAGPIE_NSEC" to stranger.nsec))
        builds.run(lib, "publishToNostr", env = mapOf("MAGPIE_NSEC" to stranger.nsec))
    }
}
