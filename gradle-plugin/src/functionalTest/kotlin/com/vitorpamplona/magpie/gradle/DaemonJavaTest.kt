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

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Run with `-Pmagpie.daemonJavaHome=<a JDK older than 21>`: resolving then fails at configuration
 * with what to do, not with a NoSuchMethodError from the signature checks on the first file.
 * Without that property (or with a newer JDK) there's nothing to check, and it passes.
 */
class DaemonJavaTest {
    private val builds = Builds()

    @AfterTest
    fun tearDown() = builds.close()

    @Test
    fun aDaemonOlderThanJava21FailsAtConfigurationWithAHint() {
        val home = System.getProperty("magpie.daemonJavaHome").orEmpty().takeIf { it.isNotBlank() } ?: return
        val version =
            File(home, "release")
                .readLines()
                .firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('"')
                ?.substringBefore('.')
                ?.toIntOrNull() ?: return
        if (version >= 21) return
        val result = builds.fail(builds.consumer("app", "com.example:greeter:1.0.0"), "help")
        assertTrue("needs Java 21 or newer; this daemon runs Java $version" in result.output, result.output)
    }
}
