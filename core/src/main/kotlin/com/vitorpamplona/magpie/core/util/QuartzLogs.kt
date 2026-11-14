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
package com.vitorpamplona.magpie.core.util

import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.LogLevel

/**
 * Sends Quartz's log to Gradle's instead of stdout, where its relay-client DEBUG lines would fill
 * every build's console. Quartz's [Log] is one global per class loader, and the engine has its own
 * loader, so this touches no other plugin's Quartz. Its warnings (a relay that dropped) show with
 * `--info`; only its errors reach the console.
 */
internal object QuartzLogs {
    fun routeTo(log: MagpieLog) {
        Log.minLevel = LogLevel.WARN
        Log.sink =
            { level, tag, message, throwable ->
                val line = "[$tag] $message" + (throwable?.let { ": $it" } ?: "")
                when (level) {
                    LogLevel.ERROR -> log.warn(line)
                    LogLevel.WARN -> log.info(line)
                    else -> log.debug(line)
                }
            }
    }
}
