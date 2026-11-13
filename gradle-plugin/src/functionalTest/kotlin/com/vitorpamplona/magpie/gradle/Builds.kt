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

import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.InMemoryBlossom
import com.vitorpamplona.magpie.testkit.InMemoryRelay
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files

/**
 * Scratch Gradle builds that apply the Magpie plugins from the directory repository the
 * `functionalTest` task published them to, against an in-memory relay and Blossom server.
 */
class Builds : AutoCloseable {
    val relay = InMemoryRelay()
    val blossom = InMemoryBlossom()
    val root: File = Files.createTempDirectory("magpie-ft").toFile()
    val cache = File(root, "magpie-cache")
    val publisher = TestIdentity.ALICE

    private val pluginRepo = System.getProperty("magpie.testRepo")
    private val pluginVersion = System.getProperty("magpie.version")

    /** Shared across tests and runs so Gradle doesn't re-download the Kotlin plugin each time. */
    private val testKitDir = File(System.getProperty("user.home"), ".magpie-testkit")

    init {
        // Machines that route dependency downloads through an init script (a Maven Central
        // mirror, say) need it in TestKit's own Gradle home too.
        File(System.getProperty("user.home"), ".gradle/init.d").listFiles()?.forEach { script ->
            script.copyTo(File(testKitDir, "init.d/${script.name}"), overwrite = true)
        }
        relay.add(
            publisher.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_BLOSSOM_SERVERS, arrayOf(arrayOf("server", blossom.url)), "")),
            publisher.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_RELAY_LIST, arrayOf(arrayOf("r", relay.url)), "")),
        )
    }

    fun dir(name: String) = File(root, name).apply { mkdirs() }

    private fun pluginManagement(): String =
        """
        pluginManagement {
            repositories {
                maven { url = uri("${File(pluginRepo).toURI()}") }
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        """.trimIndent()

    /** A java-library with one class, published as com.example:[artifact]:[version]. */
    fun javaLibrary(
        name: String,
        version: String,
        greeting: String = "hello from nostr",
        artifact: String = "greeter",
    ): File {
        val d = dir(name)
        File(d, "settings.gradle.kts").writeText(
            """
            ${pluginManagement()}
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "$artifact"
            """.trimIndent(),
        )
        File(d, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.vitorpamplona.magpie.publish") version "$pluginVersion"
            }
            group = "com.example"
            version = "$version"
            java { withSourcesJar() }
            publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
            magpie { relays("${relay.url}") }
            """.trimIndent(),
        )
        File(d, "src/main/java/com/example/Greeter.java").apply { parentFile.mkdirs() }.writeText(
            """
            package com.example;
            public class Greeter { public static String hello() { return "$greeting"; } }
            """.trimIndent(),
        )
        return d
    }

    /** An application depending on com.example:[artifact]:[version], printing its greeting. */
    fun consumer(
        name: String,
        dependency: String,
        trust: String = """publisher("${publisher.npub}")""",
        extraSettings: String = "",
    ): File {
        val d = dir(name)
        File(d, "settings.gradle.kts").writeText(
            """
            ${pluginManagement()}
            plugins { id("com.vitorpamplona.magpie") version "$pluginVersion" }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            magpie {
                relays("${relay.url}")
                group("com.example") { $trust }
                cacheDir.set(file("${cache.absolutePath}"))
                relayTimeoutMillis.set(3000L)
                $extraSettings
            }
            rootProject.name = "consumer"
            """.trimIndent(),
        )
        File(d, "build.gradle.kts").writeText(
            """
            plugins { application }
            dependencies { implementation("$dependency") }
            application { mainClass.set("app.Main") }
            """.trimIndent(),
        )
        File(d, "src/main/java/app/Main.java").apply { parentFile.mkdirs() }.writeText(
            """
            package app;
            public class Main { public static void main(String[] a) { System.out.println("GREETING: " + com.example.Greeter.hello()); } }
            """.trimIndent(),
        )
        return d
    }

    fun runner(
        projectDir: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): GradleRunner {
        val runner =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withTestKitDir(testKitDir)
                .withArguments(args.toList() + listOf("--stacktrace"))
                .withEnvironment(System.getenv() + env)
                .forwardOutput()
        // -Pmagpie.gradleVersion=8.14.3 (downloaded) or a local installation's directory.
        val version = System.getProperty("magpie.gradleVersion").orEmpty()
        return when {
            version.isBlank() -> runner
            File(version).isDirectory -> runner.withGradleInstallation(File(version))
            else -> runner.withGradleVersion(version)
        }
    }

    fun run(
        projectDir: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): BuildResult = runner(projectDir, *args, env = env).build()

    fun fail(
        projectDir: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): BuildResult = runner(projectDir, *args, env = env).buildAndFail()

    val signerEnv: Map<String, String> get() = mapOf("MAGPIE_NSEC" to publisher.nsec)

    override fun close() {
        relay.close()
        blossom.close()
        root.deleteRecursively()
    }
}
