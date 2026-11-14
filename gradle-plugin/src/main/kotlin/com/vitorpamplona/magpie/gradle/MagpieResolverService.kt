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

import com.vitorpamplona.magpie.bridge.ResolverHandle
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The local Maven repository: the engine's HTTP server on `127.0.0.1:<random port>`, started the
 * first time Gradle asks for the repository's URL and stopped when the build ends. Nothing touches
 * the network until Gradle requests a file.
 */
abstract class MagpieResolverService :
    BuildService<MagpieResolverService.Params>,
    AutoCloseable {
    interface Params : BuildServiceParameters {
        /** The engine configuration; keys in [com.vitorpamplona.magpie.bridge.EngineConfig]. */
        val config: MapProperty<String, String>

        /** `~/.gradle/magpie`: engine jars, blob and event caches, the offline mirror. */
        val cacheRoot: DirectoryProperty
    }

    @Volatile
    private var handle: ResolverHandle? = null

    /** The repository base URL; starts the server on first use. */
    val url: String
        get() = (handle ?: start()).url()

    @Synchronized
    private fun start(): ResolverHandle {
        handle?.let { return it }
        val engine = EngineLoader.engine(parameters.cacheRoot.get().asFile, withSigning = false)
        val started = engine.startResolver(parameters.config.get(), GradleEngineLogger(Logging.getLogger("magpie")))
        handle = started
        return started
    }

    override fun close() {
        handle?.close()
        handle = null
    }
}
