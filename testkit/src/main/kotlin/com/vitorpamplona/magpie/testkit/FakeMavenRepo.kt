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
package com.vitorpamplona.magpie.testkit

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes what `maven-publish` would for a Kotlin Multiplatform library: a root module whose
 * `.module` points at one module per target through `available-at`, and per-target modules with
 * their own `.module`, POM, main artifact and sources jar. Lets core tests publish without
 * running Gradle.
 */
class FakeMavenRepo(
    val dir: File,
    val group: String = "com.example",
    val root: String = "lib",
) {
    /** Writes a release of [targets] (artifact suffix to main-artifact extension) at [version]. */
    fun publish(
        version: String,
        targets: Map<String, String> = mapOf("jvm" to "jar", "linuxx64" to "klib"),
        snapshotStamp: String? = null,
        content: String = "v1",
        skipTargets: Set<String> = emptySet(),
    ): FakeMavenRepo {
        val fileVersion = snapshotStamp?.let { version.removeSuffix("-SNAPSHOT") + "-" + it } ?: version
        val rootDir = moduleDir(root, version)
        val variants =
            targets.keys.joinToString(",\n") { target ->
                """
                {
                  "name": "${target}ApiElements-published",
                  "attributes": { "org.jetbrains.kotlin.platform.type": "$target" },
                  "available-at": { "url": "../../$root-$target/$version/$root-$target-$fileVersion.module", "group": "$group", "module": "$root-$target", "version": "$version" }
                }
                """.trimIndent()
            }
        File(rootDir, "$root-$fileVersion.module").writeText(moduleJson(root, version, variants))
        File(rootDir, "$root-$fileVersion.pom").writeText(pom(root, version))
        File(rootDir, "$root-$fileVersion.jar").writeBytes(jar("META-INF/$root.kotlin_module" to content))
        File(rootDir, "$root-$fileVersion-sources.jar").writeBytes(jar("commonMain/Lib.kt" to "// $content"))
        File(rootDir, "$root-$fileVersion-kotlin-tooling-metadata.json").writeText("""{"schemaVersion":"1.1.0","buildSystem":"Gradle"}""")
        // Gradle's own sidecars, which the scanner must ignore.
        File(rootDir, "$root-$fileVersion.pom.sha1").writeText("0".repeat(40))
        File(rootDir, "$root-$fileVersion.pom.md5").writeText("0".repeat(32))

        targets.forEach { (target, extension) ->
            if (target in skipTargets) return@forEach
            val artifact = "$root-$target"
            val d = moduleDir(artifact, version)
            val main = "$artifact-$fileVersion.$extension"
            val files =
                """
                {
                  "name": "${target}ApiElements-published",
                  "attributes": { "org.jetbrains.kotlin.platform.type": "$target" },
                  "files": [ { "name": "$main", "url": "$main", "size": 1, "sha256": "x" } ]
                }
                """.trimIndent()
            File(d, "$artifact-$fileVersion.module").writeText(moduleJson(artifact, version, files))
            File(d, "$artifact-$fileVersion.pom").writeText(pom(artifact, version))
            File(d, main).writeBytes(jar("$target/Lib.class" to "$content-$target"))
            File(d, "$artifact-$fileVersion-sources.jar").writeBytes(jar("$target/Lib.kt" to "// $content-$target"))
        }
        return this
    }

    /** Only the files of [targets] (plus the root module if [withRoot]), for a partial host. */
    fun copyOf(
        destination: File,
        artifacts: Set<String>,
    ): FakeMavenRepo {
        val groupDir = File(dir, group.replace('.', '/'))
        groupDir.listFiles()?.filter { it.name in artifacts }?.forEach { artifactDir ->
            artifactDir.copyRecursively(File(destination, group.replace('.', '/') + "/" + artifactDir.name), overwrite = true)
        }
        return FakeMavenRepo(destination, group, root)
    }

    private fun moduleDir(
        artifact: String,
        version: String,
    ): File = File(dir, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }

    private fun moduleJson(
        artifact: String,
        version: String,
        variants: String,
    ) = """
        {
          "formatVersion": "1.1",
          "component": { "group": "$group", "module": "$artifact", "version": "$version" },
          "createdBy": { "gradle": { "version": "9.8.2" } },
          "variants": [
        $variants
          ]
        }
        """.trimIndent()

    private fun pom(
        artifact: String,
        version: String,
    ) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <!-- do_not_remove: published-with-gradle-metadata -->
          <modelVersion>4.0.0</modelVersion>
          <groupId>$group</groupId>
          <artifactId>$artifact</artifactId>
          <version>$version</version>
        </project>
        """.trimIndent()

    companion object {
        fun jar(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }
}
