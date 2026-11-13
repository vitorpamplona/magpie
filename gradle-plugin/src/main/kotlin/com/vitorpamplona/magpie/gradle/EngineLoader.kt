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
import com.vitorpamplona.magpie.bridge.EngineLogger
import com.vitorpamplona.magpie.bridge.MagpieEngine
import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the Magpie engine from the jars nested in this plugin (`META-INF/magpie/engine/`), in a
 * class loader of its own whose only link to the outside is the bridge package. The engine brings
 * its own Kotlin stdlib, coroutines, OkHttp and Quartz, so neither Gradle's embedded Kotlin nor any
 * other plugin's dependencies can clash with it; that does what shading and relocating would,
 * without rewriting Quartz's bytecode.
 *
 * The daemon loads the engine without the secp256k1 JNI ([withSigning] = false): resolution
 * verifies signatures in pure Kotlin. Signing loads it in a worker process.
 */
internal object EngineLoader {
    private const val ROOT = "META-INF/magpie/engine/"
    private val loaders = ConcurrentHashMap<String, ClassLoader>()

    private class Entry(
        val kind: String,
        val name: String,
        val sha256: String,
    )

    private fun index(): List<Entry> {
        val text =
            EngineLoader::class.java.classLoader
                .getResourceAsStream(ROOT + "index.txt")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("The Magpie plugin jar has no engine (missing ${ROOT}index.txt)")
        return text.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.trim().split(' ')
            Entry(parts[0], parts[1], parts[2])
        }
    }

    /**
     * The engine jars, under `cacheRoot/engine/<id>/`. This runs while Gradle may be storing the
     * configuration cache, where every file this (instrumented) plugin code checks becomes a cache
     * input; so it never checks: it probes by loading the engine class, and extracts the whole
     * directory atomically only when that fails.
     */
    fun classpath(
        cacheRoot: File,
        withSigning: Boolean,
    ): List<File> = layout(cacheRoot, withSigning).second

    private fun layout(
        cacheRoot: File,
        withSigning: Boolean,
    ): Pair<File, List<File>> {
        val entries = index()
        val id = sha256(entries.joinToString("\n") { "${it.kind} ${it.name} ${it.sha256}" }.toByteArray()).substring(0, 16)
        val dir = File(cacheRoot, "engine/$id")
        return dir to entries.filter { it.kind == "runtime" || (withSigning && it.kind == "signing") }.map { File(dir, it.name) }
    }

    private fun extractAll(dir: File) {
        val entries = index()
        Files.createDirectories(dir.parentFile.toPath())
        val tmp = Files.createTempDirectory(dir.parentFile.toPath(), dir.name + ".tmp")
        for (entry in entries) {
            val bytes =
                EngineLoader::class.java.classLoader
                    .getResourceAsStream(ROOT + entry.name)
                    ?.use { it.readBytes() }
                    ?: throw IllegalStateException("The Magpie plugin jar is missing $ROOT${entry.name}")
            check(sha256(bytes) == entry.sha256) { "Corrupt engine jar ${entry.name} in the Magpie plugin" }
            Files.write(tmp.resolve(entry.name), bytes)
        }
        try {
            Files.move(tmp, dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Another build extracted it first; use theirs.
            for (entry in entries) Files.deleteIfExists(tmp.resolve(entry.name))
            Files.deleteIfExists(tmp)
        }
    }

    fun engine(
        cacheRoot: File,
        withSigning: Boolean,
    ): MagpieEngine {
        val (dir, jars) = layout(cacheRoot, withSigning)
        val key = jars.joinToString(File.pathSeparator) { it.path }
        loaders[key]?.let { return instantiate(it) }
        val first = newLoader(jars)
        val loader =
            if (probe(first)) {
                first
            } else {
                first.close()
                extractAll(dir)
                newLoader(jars).also { check(probe(it)) { "Could not load the Magpie engine from $dir" } }
            }
        return instantiate(loaders.putIfAbsent(key, loader) ?: loader)
    }

    private fun newLoader(jars: List<File>) = URLClassLoader("magpie-engine", jars.map { it.toURI().toURL() }.toTypedArray(), BridgeOnlyClassLoader(EngineLoader::class.java.classLoader))

    private fun probe(loader: ClassLoader): Boolean =
        try {
            loader.loadClass(EngineConfig.ENGINE_CLASS)
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }

    private fun instantiate(loader: ClassLoader): MagpieEngine =
        withContextClassLoader(loader) {
            loader.loadClass(EngineConfig.ENGINE_CLASS).getDeclaredConstructor().newInstance() as MagpieEngine
        }

    fun <T> withContextClassLoader(
        loader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The JDK, plus the bridge interfaces from the plugin's loader; nothing else leaks in. */
private class BridgeOnlyClassLoader(
    private val plugin: ClassLoader,
) : ClassLoader("magpie-bridge", getPlatformClassLoader()) {
    override fun findClass(name: String): Class<*> {
        if (name.startsWith("com.vitorpamplona.magpie.bridge.")) return plugin.loadClass(name)
        throw ClassNotFoundException(name)
    }
}

/** Gradle's logger, handed to the engine. */
internal class GradleEngineLogger(
    private val logger: Logger,
) : EngineLogger {
    override fun error(message: String) = logger.error(message)

    override fun warn(message: String) = logger.warn(message)

    override fun lifecycle(message: String) = logger.lifecycle(message)

    override fun info(message: String) = logger.info(message)

    override fun debug(message: String) = logger.debug(message)
}
