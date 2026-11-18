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

import com.vitorpamplona.magpie.core.publish.RepoScanner
import com.vitorpamplona.magpie.core.resolve.MetadataGenerator
import com.vitorpamplona.magpie.core.resolve.VersionEntry
import com.vitorpamplona.magpie.core.util.Hashing
import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.testkit.TestIdentity
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares what Magpie serves with what Maven Central served for a real Kotlin Multiplatform
 * library (Quartz 2.15.1's root module, fetched into `src/test/resources/golden/central`).
 */
class GoldenCentralTest {
    private val golden = File(javaClass.getResource("/golden/central")!!.toURI())
    private val modulePath = "com/vitorpamplona/quartz/quartz"
    private val world = MagpieWorld()

    @AfterTest
    fun tearDown() = world.close()

    @Test
    fun artifactMetadataMatchesCentralByteForByte() {
        val central = File(golden, "$modulePath/maven-metadata.xml").readText()
        val versions = Regex("<version>([^<]+)</version>").findAll(central).map { it.groupValues[1] }.toList()
        val lastUpdated = Regex("<lastUpdated>(\\d+)</lastUpdated>").find(central)!!.groupValues[1]

        // One release event per version, in Central's (publication) order.
        val entries = versions.mapIndexed { i, v -> VersionEntry(v, 1_600_000_000L + i) }
        val ours =
            MetadataGenerator
                .artifactMetadata(ModuleId("com.vitorpamplona.quartz", "quartz"), entries)
                .replace(Regex("<lastUpdated>\\d+</lastUpdated>"), "<lastUpdated>$lastUpdated</lastUpdated>")
        assertEquals(central, ours)

        // Same bytes, so the same sidecars: compare with every one Central serves.
        ChecksumAlgorithm.entries.forEach { algorithm ->
            val sidecar = File(golden, "$modulePath/maven-metadata.xml.${algorithm.extension}")
            if (sidecar.isFile) assertEquals(sidecar.readText().trim(), Hashing.checksum(ours.toByteArray(), algorithm), algorithm.name)
        }
    }

    @Test
    fun filesAndChecksumsMatchCentral() {
        // Publish Central's files as a release (signed directly: the root module's .module points
        // at target modules this fixture leaves out, which the publisher would rightly refuse).
        val alice = TestIdentity.ALICE
        world.setUpPublisher(alice)
        val scanned = RepoScanner.scan(golden, "com.vitorpamplona.quartz", "2.15.1", "quartz")
        scanned.files.values.forEach { world.blossom.put(it.readBytes()) }
        world.blossom.put(scanned.manifest.toBytes())
        world.relay.add(alice.sign(MagpieEvents.release(scanned.manifest, servers = listOf(world.blossom.url), createdAt = 1_700_000_000)))

        val stack =
            com.vitorpamplona.magpie.core.engine.ResolverStack.create(
                mapOf(
                    com.vitorpamplona.magpie.bridge.EngineConfig.INDEX_RELAYS to world.relay.url,
                    com.vitorpamplona.magpie.bridge.EngineConfig.GROUPS to "com.vitorpamplona.quartz=${alice.npub}",
                    com.vitorpamplona.magpie.bridge.EngineConfig.CACHE_DIR to world.dir("cache").path,
                ),
                world.log,
            )
        stack.use {
            var compared = 0
            File(golden, "$modulePath/2.15.1").listFiles()!!.filter { ChecksumAlgorithm.fromFileName(it.name) != null }.forEach { sidecar ->
                val served = stack.get("$modulePath/2.15.1/${sidecar.name}").text()
                assertEquals(sidecar.readText().trim(), served, sidecar.name)
                compared++
            }
            File(golden, "$modulePath/2.15.1").listFiles()!!.filter { ChecksumAlgorithm.fromFileName(it.name) == null }.forEach { file ->
                assertEquals(Hashing.sha256(file), Hashing.sha256(stack.get("$modulePath/2.15.1/${file.name}").bytes()), file.name)
                // Central serves no .sha256/.sha512 for these; ours must still be right.
                assertEquals(Hashing.checksum(file, ChecksumAlgorithm.SHA512), stack.get("$modulePath/2.15.1/${file.name}.sha512").text())
            }
            assertEquals(true, compared >= 5, "compared $compared sidecars")
        }
    }
}
