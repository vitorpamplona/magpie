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

import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Publishes a Kotlin Multiplatform library and consumes it. The targets come from
 * `-Pmagpie.kmpTargets` (comma-separated; default `jvm`): CI's Linux job runs
 * `jvm,js,wasmJs,linuxX64,android` (android when ANDROID_HOME is set), the macOS job adds
 * `iosSimulatorArm64,macosArm64`.
 */
class KmpEndToEndTest {
    private val builds = Builds()
    private val targets =
        System
            .getProperty("magpie.kmpTargets", "jvm")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it != "android" || System.getenv("ANDROID_HOME") != null }

    @AfterTest
    fun tearDown() = builds.close()

    private fun targetBlock(): String =
        targets.joinToString("\n") { target ->
            when (target) {
                "jvm" -> "jvm()"
                "js" -> "js { nodejs() }"
                "wasmJs" -> "@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { nodejs() }"
                "android" -> "android { namespace = \"com.example.sample\"; compileSdk = 37; minSdk = 24 }"
                else -> "$target()"
            }
        }

    /** The sample library: one common object, every configured target. */
    private fun kmpLibrary(
        name: String,
        version: String,
        partialsDir: File? = null,
    ): File {
        val d = builds.dir(name)
        val android = "android" in targets
        File(d, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${File(System.getProperty("magpie.testRepo")).toURI()}") }
                    gradlePluginPortal()
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "sample"
            """.trimIndent(),
        )
        File(d, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.4.21"
                ${if (android) "id(\"com.android.kotlin.multiplatform.library\") version \"9.5.0\"" else ""}
                id("com.vitorpamplona.magpie.publish") version "${System.getProperty("magpie.version")}"
            }
            group = "com.example"
            version = "$version"
            kotlin {
                ${targetBlock()}
            }
            magpie {
                nip05BaseUrl.set("${builds.nip05Base}")
                ${partialsDir?.let { "partialsDir.set(file(\"${it.absolutePath}\"))" } ?: ""}
            }

            // Simulates a multi-host CI: -PmagpieHost=a publishes the root and JVM modules, b the rest.
            val host = providers.gradleProperty("magpieHost").orNull
            tasks.withType<PublishToMavenRepository>().configureEach {
                val pub = name.removePrefix("publish").substringBefore("PublicationTo").replaceFirstChar { it.lowercase() }
                onlyIf { host == null || (host == "a") == (pub == "kotlinMultiplatform" || pub == "jvm") }
            }
            """.trimIndent(),
        )
        // The relays come from a Gradle property, as a build that applies the plugin conditionally sets them.
        File(d, "gradle.properties").appendText("\nmagpie.relays=${builds.relay.url}\n")
        File(d, "src/commonMain/kotlin/com/example/Greeter.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package com.example
            object Greeter { fun hello(): String = "hello from kmp on nostr" }
            """.trimIndent(),
        )
        return d
    }

    /** A JVM app on the library's root coordinate: Gradle module metadata picks the JVM variant. */
    private fun jvmConsumer(name: String): File {
        val d = builds.consumer(name, "com.example:sample:1.0.0")
        File(d, "build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.4.21"; application }
            dependencies { implementation("com.example:sample:1.0.0") }
            application { mainClass.set("app.MainKt") }
            """.trimIndent(),
        )
        File(d, "src/main/java/app/Main.java").delete()
        File(d, "src/main/kotlin/app/Main.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package app
            fun main() { println("GREETING: " + com.example.Greeter.hello()) }
            """.trimIndent(),
        )
        return d
    }

    /** A KMP app with the same targets, compiling against every published variant. */
    private fun kmpConsumer(name: String): File {
        val d = builds.consumer(name, "com.example:sample:1.0.0")
        File(d, "src/main/java/app/Main.java").delete()
        File(d, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.4.21"
                ${if ("android" in targets) "id(\"com.android.kotlin.multiplatform.library\") version \"9.5.0\"" else ""}
            }
            kotlin {
                ${targetBlock().replace("com.example.sample", "app.sample")}
                sourceSets { commonMain.dependencies { implementation("com.example:sample:1.0.0") } }
            }
            """.trimIndent(),
        )
        File(d, "src/commonMain/kotlin/app/Use.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package app
            fun greeting(): String = com.example.Greeter.hello()
            """.trimIndent(),
        )
        File(d, "settings.gradle.kts").writeText(
            File(d, "settings.gradle.kts").readText().replace("dependencyResolutionManagement { repositories { mavenCentral() } }", "dependencyResolutionManagement { repositories { google(); mavenCentral() } }"),
        )
        return d
    }

    private fun compileTasks(): List<String> =
        targets.map { target ->
            when (target) {
                "jvm" -> "compileKotlinJvm"
                "js" -> "compileKotlinJs"
                "wasmJs" -> "compileKotlinWasmJs"
                "android" -> "compileAndroidMain"
                else -> "compileKotlin" + target.replaceFirstChar { it.uppercase() }
            }
        }

    @Test
    fun publishAndConsumeAMultiplatformLibrary() {
        builds.run(kmpLibrary("sample", "1.0.0"), "publishToNostr", env = builds.signerEnv)
        val release = MagpieEvents.wrap(builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).single()) as MagpieReleaseEvent
        // One release for the whole library: the root module plus one module per target.
        assertEquals(targets.size + 1, release.moduleKeys().size, release.moduleKeys().toString())

        if ("jvm" in targets) {
            val run = builds.run(jvmConsumer("jvm-app"), "run")
            assertTrue("GREETING: hello from kmp on nostr" in run.output, run.output)
        }
        builds.run(kmpConsumer("kmp-app"), *compileTasks().toTypedArray())
    }

    @Test
    fun partialPublishesFromTwoHostsAndOneClose() {
        if (targets.size < 2) return // needs a second target for the second host
        val shared = builds.dir("partials")
        val hostA = kmpLibrary("host-a", "1.0.0", partialsDir = shared)
        val hostB = kmpLibrary("host-b", "1.0.0", partialsDir = shared)
        builds.run(hostA, "publishToNostr", "--partial", "--partial-name=a", "-PmagpieHost=a", env = builds.signerEnv)
        builds.run(hostB, "publishToNostr", "--partial", "--partial-name=b", "-PmagpieHost=b", env = builds.signerEnv)
        assertEquals(0, builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size, "no release before close")

        builds.run(hostA, "closeNostrRelease", env = builds.signerEnv)
        val release = MagpieEvents.wrap(builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).single()) as MagpieReleaseEvent
        val manifest = Manifest.parse(builds.blossom.blobs.getValue(release.manifest()!!.sha256))
        assertEquals(targets.size + 1, manifest.artifacts.size, manifest.artifacts.toString())
        builds.run(kmpConsumer("kmp-app"), *compileTasks().toTypedArray())
    }

    @Test
    fun closeRefusesWhenAHostDidNotPublish() {
        if (targets.size < 2) return
        val shared = builds.dir("partials")
        val hostA = kmpLibrary("host-a", "1.0.0", partialsDir = shared)
        builds.run(hostA, "publishToNostr", "--partial", "--partial-name=a", "-PmagpieHost=a", env = builds.signerEnv)
        val result = builds.fail(hostA, "closeNostrRelease", env = builds.signerEnv)
        assertTrue("Refusing to sign" in result.output, result.output)
        assertEquals(0, builds.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size)
    }
}
