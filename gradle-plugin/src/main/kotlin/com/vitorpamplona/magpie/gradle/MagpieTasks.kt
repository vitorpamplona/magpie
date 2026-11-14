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

import com.vitorpamplona.magpie.bridge.EngineConfig
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Runs one engine task in a worker process: signing loads the secp256k1 JNI, which must never load
 * in the Gradle daemon (a native library binds to one class loader per JVM). Secrets are read from
 * the environment at execution, so they never land in the configuration cache.
 */
abstract class MagpieEngineTask : DefaultTask() {
    @get:Internal
    abstract val cacheRoot: DirectoryProperty

    @get:Input
    abstract val engineTask: Property<String>

    @get:Input
    abstract val relays: ListProperty<String>

    @get:Input
    abstract val archivalRelays: ListProperty<String>

    @get:Input
    abstract val bunkerEnv: Property<String>

    @get:Input
    abstract val nsecEnv: Property<String>

    /** `domain`, a NIP-05 identity, or `off`: see [MagpiePublishExtension.verifyReleaseKey]. */
    @get:Input
    abstract val releaseKeyCheck: Property<String>

    @get:Input
    @get:Optional
    abstract val nip05BaseUrl: Property<String>

    @get:Inject
    abstract val workers: WorkerExecutor

    @get:Inject
    abstract val toolchains: JavaToolchainService

    /** Task-specific engine settings. */
    @Internal
    protected open fun extraConfig(): Map<String, String> = emptyMap()

    @TaskAction
    fun run() {
        if (relays.get().isEmpty()) throw GradleException("Magpie: set magpie { relays(\"wss://…\") } to read your server and relay lists from.")
        val config =
            buildMap {
                put(EngineConfig.INDEXER_RELAYS, relays.get().joinToString("\n"))
                put(EngineConfig.PUBLISH_RELAYS, archivalRelays.get().joinToString("\n"))
                put(EngineConfig.RELEASE_KEY_CHECK, releaseKeyCheck.get())
                nip05BaseUrl.orNull?.let { put(EngineConfig.NIP05_BASE_URL, it) }
                System.getenv(bunkerEnv.get())?.takeIf { it.isNotBlank() }?.let { put(EngineConfig.BUNKER_URI, it) }
                System.getenv(nsecEnv.get())?.takeIf { it.isNotBlank() }?.let { put(EngineConfig.NSEC, it) }
                putAll(extraConfig())
            }
        if (EngineConfig.BUNKER_URI !in config && EngineConfig.NSEC !in config) {
            throw GradleException(
                "Magpie: no signer. Set ${bunkerEnv.get()} to a NIP-46 bunker:// URI (recommended for CI) or ${nsecEnv.get()} to a secret key.",
            )
        }
        val resultFile = File(temporaryDir, "result.properties")
        resultFile.delete()
        val queue =
            workers.processIsolation {
                // The secp256k1 JNI targets Java 21.
                if (Runtime.version().feature() < 21) {
                    forkOptions.executable =
                        toolchains
                            .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                            .get()
                            .executablePath.asFile.absolutePath
                }
                forkOptions.maxHeapSize = "512m"
            }
        queue.submit(MagpieWorkAction::class.java) {
            task.set(engineTask)
            this.config.set(config)
            cacheRoot.set(this@MagpieEngineTask.cacheRoot)
            result.set(resultFile)
        }
        queue.await()
        if (resultFile.isFile) onResult(resultFile.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') })
    }

    protected open fun onResult(result: Map<String, String>) {}
}

abstract class PublishToNostrTask : MagpieEngineTask() {
    @get:Internal
    abstract val repoDir: DirectoryProperty

    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val artifactId: Property<String>

    @get:Internal
    abstract val partialsDir: DirectoryProperty

    @get:Input
    abstract val partialName: Property<String>

    @get:Input
    abstract val partial: Property<Boolean>

    @get:Input
    abstract val dryRun: Property<Boolean>

    init {
        partial.convention(false)
        dryRun.convention(false)
    }

    @Option(option = "partial", description = "Upload this host's files and write a partial manifest; closeNostrRelease signs later.")
    fun setPartialFlag(value: Boolean) {
        partial.set(value)
    }

    @Option(option = "partial-name", description = "Name of this host's partial manifest (default: OS and architecture).")
    fun setPartialNameOption(value: String) {
        partialName.set(value)
    }

    @Option(option = "dry-run", description = "Check everything (server lists, overwrite, preflight) but upload and sign nothing.")
    fun setDryRunFlag(value: Boolean) {
        dryRun.set(value)
    }

    override fun extraConfig(): Map<String, String> =
        mapOf(
            EngineConfig.REPO_DIR to repoDir.get().asFile.absolutePath,
            EngineConfig.GROUP to groupId.get(),
            EngineConfig.VERSION to version.get(),
            EngineConfig.ARTIFACT to artifactId.get(),
            EngineConfig.PARTIAL to partial.get().toString(),
            EngineConfig.PARTIAL_DIR to partialsDir.get().asFile.absolutePath,
            EngineConfig.PARTIAL_NAME to partialName.get(),
            EngineConfig.DRY_RUN to dryRun.get().toString(),
        )

    override fun onResult(result: Map<String, String>) {
        result["event"]?.let { logger.lifecycle("Magpie release event: $it") }
        result["partialManifest"]?.let { logger.lifecycle("Magpie partial manifest: $it (copy it to the closing host's ${partialsDir.get().asFile})") }
    }
}

abstract class CloseNostrReleaseTask : MagpieEngineTask() {
    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val artifactId: Property<String>

    @get:Internal
    abstract val partialsDir: DirectoryProperty

    @Option(option = "partials", description = "Directory holding every host's partial manifest.")
    fun setPartialsOption(path: String) {
        partialsDir.set(File(path))
    }

    override fun extraConfig(): Map<String, String> =
        mapOf(
            EngineConfig.REPO_DIR to partialsDir.get().asFile.absolutePath,
            EngineConfig.GROUP to groupId.get(),
            EngineConfig.VERSION to version.get(),
            EngineConfig.ARTIFACT to artifactId.get(),
            EngineConfig.PARTIAL_DIR to partialsDir.get().asFile.absolutePath,
        )

    override fun onResult(result: Map<String, String>) {
        result["event"]?.let { logger.lifecycle("Magpie release event: $it") }
    }
}

abstract class CreateServerListTask : MagpieEngineTask() {
    @get:Input
    abstract val servers: ListProperty<String>

    @Option(option = "server", description = "A Blossom server base URL; repeat for more.")
    fun setServerOption(values: List<String>) {
        servers.set(values)
    }

    override fun extraConfig(): Map<String, String> {
        if (servers.get().isEmpty()) throw GradleException("Give at least one --server=https://…")
        return mapOf(EngineConfig.SERVERS to servers.get().joinToString("\n"))
    }
}

/** Runs in the worker process: loads the engine with signing and runs the task. */
abstract class MagpieWorkAction : WorkAction<MagpieWorkAction.Params> {
    interface Params : WorkParameters {
        val task: Property<String>
        val config: MapProperty<String, String>
        val cacheRoot: DirectoryProperty
        val result: RegularFileProperty
    }

    override fun execute() {
        val engine = EngineLoader.engine(parameters.cacheRoot.get().asFile, withSigning = true)
        val result =
            try {
                engine.runTask(parameters.task.get(), parameters.config.get(), GradleEngineLogger(Logging.getLogger("magpie")))
            } catch (e: Exception) {
                // The engine's exception classes don't exist outside its class loader.
                throw GradleException("Magpie: ${e.message ?: e.javaClass.simpleName}")
            }
        parameters.result
            .get()
            .asFile
            .writeText(result.entries.joinToString("\n") { "${it.key}=${it.value.replace("\n", ",")}" })
    }
}
