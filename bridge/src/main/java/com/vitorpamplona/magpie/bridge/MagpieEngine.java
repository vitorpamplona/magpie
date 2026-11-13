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
package com.vitorpamplona.magpie.bridge;

import java.util.Map;

/**
 * The engine's entry point, implemented by {@code com.vitorpamplona.magpie.core.engine.MagpieEngineImpl}
 * and loaded through {@link EngineConfig#ENGINE_CLASS}. Configuration crosses the class-loader
 * boundary as string maps (keys in {@link EngineConfig}); lists are newline-separated.
 */
public interface MagpieEngine {
    /** Starts the local Maven repository server on 127.0.0.1 and returns a handle to it. */
    ResolverHandle startResolver(Map<String, String> config, EngineLogger logger) throws Exception;

    /**
     * Runs one publishing task ({@link EngineConfig#TASK_PUBLISH}, {@link EngineConfig#TASK_CLOSE},
     * {@link EngineConfig#TASK_CREATE_SERVER_LIST}, {@link EngineConfig#TASK_YANK}) and returns a
     * summary (event ids, manifest hash).
     */
    Map<String, String> runTask(String task, Map<String, String> config, EngineLogger logger) throws Exception;
}
