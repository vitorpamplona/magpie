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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenPathTest {
    private fun file(path: String): MavenPath.ArtifactFile = assertIs<MavenPath.ArtifactFile>(MavenPath.parse(path), path)

    @Test
    fun plainJar() {
        val f = file("com/vitorpamplona/quartz/quartz-jvm/2.15.1/quartz-jvm-2.15.1.jar")
        assertEquals(Coordinate("com.vitorpamplona.quartz", "quartz-jvm", "2.15.1"), f.coordinate)
        assertNull(f.classifier)
        assertEquals("jar", f.extension)
        assertNull(f.checksum)
        assertEquals("quartz-jvm/2.15.1/quartz-jvm-2.15.1.jar", f.manifestKey)
    }

    @Test
    fun leadingSlashAndQueryAreIgnored() {
        val f = file("/com/example/lib/1.0/lib-1.0.pom?x=1")
        assertEquals("pom", f.extension)
    }

    @Test
    fun gradleModuleFile() {
        val f = file("org/jetbrains/kotlinx/kotlinx-datetime/0.6.1/kotlinx-datetime-0.6.1.module")
        assertEquals("module", f.extension)
        assertEquals("org.jetbrains.kotlinx", f.coordinate.group)
    }

    @Test
    fun klibAndAar() {
        assertEquals("klib", file("a/b/lib-linuxx64/1.0/lib-linuxx64-1.0.klib").extension)
        assertEquals("aar", file("a/b/lib-android/1.0/lib-android-1.0.aar").extension)
    }

    @Test
    fun sourcesAndJavadocClassifiers() {
        val sources = file("com/example/lib-jvm/1.0.0/lib-jvm-1.0.0-sources.jar")
        assertEquals("sources", sources.classifier)
        assertEquals("jar", sources.extension)
        assertEquals("javadoc", file("com/example/lib/1.0.0/lib-1.0.0-javadoc.jar").classifier)
    }

    @Test
    fun cinteropKlib() {
        val f = file("com/vitorpamplona/quartz/quartz-iosarm64/2.15.1/quartz-iosarm64-2.15.1-cinterop-Secp256k1.klib")
        assertEquals("cinterop-Secp256k1", f.classifier)
        assertEquals("klib", f.extension)
    }

    @Test
    fun kotlinToolingMetadata() {
        val f = file("com/example/lib/1.0/lib-1.0-kotlin-tooling-metadata.json")
        assertEquals("kotlin-tooling-metadata", f.classifier)
        assertEquals("json", f.extension)
    }

    @Test
    fun signatureAndItsChecksum() {
        val asc = file("com/example/lib/1.0/lib-1.0.jar.asc")
        assertTrue(asc.isSignature)
        assertEquals("jar.asc", asc.extension)

        val ascSha = file("com/example/lib/1.0/lib-1.0.jar.asc.sha1")
        assertEquals(ChecksumAlgorithm.SHA1, ascSha.checksum)
        assertEquals("lib-1.0.jar.asc", ascSha.fileName)
    }

    @Test
    fun everyChecksumSuffix() {
        ChecksumAlgorithm.entries.forEach { algorithm ->
            val f = file("com/example/lib/1.0/lib-1.0.module.${algorithm.extension}")
            assertEquals(algorithm, f.checksum)
            assertEquals("lib-1.0.module", f.fileName)
            assertEquals("com/example/lib/1.0/lib-1.0.module.${algorithm.extension}", f.toPath())
        }
    }

    @Test
    fun versionsWithDashes() {
        val f = file("com/example/lib/1.0.0-RC1/lib-1.0.0-RC1-sources.jar")
        assertEquals("1.0.0-RC1", f.coordinate.version)
        assertEquals("sources", f.classifier)
    }

    @Test
    fun artifactNamesThatPrefixOthers() {
        // "lib" vs "lib-jvm": the directory decides, not the file name.
        val f = file("com/example/lib-jvm/1.0/lib-jvm-1.0.jar")
        assertEquals("lib-jvm", f.coordinate.artifact)
        assertNull(f.classifier)
        assertNull(
            MavenPath.parse("com/example/lib/1.0/lib-jvm-1.0.jar")?.let { it as? MavenPath.ArtifactFile }?.classifier?.let {
                if (it ==
                    "jvm"
                ) {
                    null
                } else {
                    it
                }
            },
        )
    }

    @Test
    fun timestampedSnapshot() {
        val f = file("com/example/lib/1.0-SNAPSHOT/lib-1.0-20240102.030405-7-sources.jar")
        assertEquals("20240102.030405-7", f.snapshotStamp)
        assertEquals("sources", f.classifier)
        assertTrue(f.coordinate.isSnapshot)

        val plain = file("com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom")
        assertNull(plain.snapshotStamp)
    }

    @Test
    fun metadata() {
        val artifactLevel = assertIs<MavenPath.ArtifactMetadata>(MavenPath.parse("com/example/lib/maven-metadata.xml"))
        assertEquals(ModuleId("com.example", "lib"), artifactLevel.module)

        val sha = assertIs<MavenPath.ArtifactMetadata>(MavenPath.parse("com/example/lib/maven-metadata.xml.sha256"))
        assertEquals(ChecksumAlgorithm.SHA256, sha.checksum)

        val versionLevel = assertIs<MavenPath.VersionMetadata>(MavenPath.parse("com/example/lib/1.0-SNAPSHOT/maven-metadata.xml"))
        assertEquals("1.0-SNAPSHOT", versionLevel.coordinate.version)
    }

    @Test
    fun rejectsTraversalAndForeignFiles() {
        assertNull(MavenPath.parse("com/../etc/passwd/1.0/passwd-1.0.jar"))
        assertNull(MavenPath.parse("com/example/lib/1.0/other-1.0.jar"))
        assertNull(MavenPath.parse("com/example/lib/1.0/lib-2.0.jar"))
        assertNull(MavenPath.parse("com/example/lib/1.0/lib-1.0"))
        assertNull(MavenPath.parse("com//lib/1.0/lib-1.0.jar"))
        assertNull(MavenPath.parse("lib"))
        assertNull(MavenPath.parse(""))
    }

    @Test
    fun theVersionMustEndAtADashOrDot() {
        assertNull(MavenPath.parse("com/example/lib/1.0/lib-1.0.1.jar"))
        assertNull(MavenPath.parse("com/example/lib/1.0/lib-1.0x.jar"))
    }

    @Test
    fun classifiersMayContainDots() {
        val f = file("com/example/lib/1.0/lib-1.0-jdk1.8.jar")
        assertEquals("jdk1.8", f.classifier)
        assertEquals("jar", f.extension)
        assertEquals("tar.gz", file("com/example/lib/1.0/lib-1.0-dist.tar.gz").extension)
        assertEquals("jar.asc", file("com/example/lib/1.0/lib-1.0-jdk1.8.jar.asc").extension)
    }

    @Test
    fun aReleaseDirectoryHasNoMetadata() {
        assertNull(MavenPath.parse("com/example/lib/1.0/maven-metadata.xml"))
        assertIs<MavenPath.ArtifactMetadata>(MavenPath.parse("com/example/lib/maven-metadata.xml"))
    }

    @Test
    fun snapshotStampsOrderByBuildNumber() {
        val stamps = listOf("20261113.101010-9", "20261113.101010-10", "20261112.235959-99")
        assertEquals("20261113.101010-10", stamps.maxWithOrNull(MavenPath.SNAPSHOT_STAMP_ORDER))
    }
}
