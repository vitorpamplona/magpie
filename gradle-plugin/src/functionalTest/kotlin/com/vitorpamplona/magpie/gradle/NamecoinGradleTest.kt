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

import org.gradle.testkit.runner.TaskOutcome
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamecoinGradleTest {
    private val builds = Builds()

    @AfterTest
    fun tearDown() = builds.close()

    @Test
    fun printTaskUsesRepeatableRelayOptionsWithoutPublishing() {
        val lib = builds.javaLibrary("lib", "1.0.0", extraMagpie = "verifyReleaseKey.set(false)")
        val originalEvents =
            builds.relay
                .all()
                .map { it.id }
                .toSet()
        val result =
            builds.run(
                lib,
                "printMagpieNamecoinValue",
                "--relay=wss://one.example.com",
                "--relay=wss://two.example.com/nostr",
                env = builds.signerEnv,
            )
        val key = builds.publisher.pubKey
        val expected =
            """{"nostr":{"names":{"releases":"$key"},"relays":{"$key":["wss://one.example.com/","wss://two.example.com/nostr"]}}}"""
        assertEquals(TaskOutcome.SUCCESS, result.task(":printMagpieNamecoinValue")?.outcome)
        assertTrue(expected in result.output.lines(), result.output)
        assertEquals(
            originalEvents,
            builds.relay
                .all()
                .map { it.id }
                .toSet(),
        )
        assertEquals(0, builds.relay.reqCount.get())
        assertEquals(0, builds.blossom.uploads.get())
        assertTrue(builds.blossom.authEvents.isEmpty())
    }

    @Test
    fun printTaskUsesConfiguredRelayHintsDeterministically() {
        val lib =
            builds.javaLibrary(
                "lib",
                "1.0.0",
                extraMagpie =
                    """
                    verifyReleaseKey.set(false)
                    namecoinRelays("wss://one.example.com", "wss://two.example.com/nostr")
                    """.trimIndent(),
            )
        val first = builds.run(lib, "printMagpieNamecoinValue", env = builds.signerEnv)
        val second = builds.run(lib, "printMagpieNamecoinValue", env = builds.signerEnv)
        val key = builds.publisher.pubKey
        val expected =
            """{"nostr":{"names":{"releases":"$key"},"relays":{"$key":["wss://one.example.com/","wss://two.example.com/nostr"]}}}"""
        assertTrue(expected in first.output.lines(), first.output)
        assertTrue(expected in second.output.lines(), second.output)
        assertEquals(0, builds.blossom.uploads.get())
    }

    @Test
    fun printTaskRejectsBitRelayEndpoints() {
        val lib = builds.javaLibrary("lib", "1.0.0", extraMagpie = "verifyReleaseKey.set(false)")
        val originalEvents =
            builds.relay
                .all()
                .map { it.id }
                .toSet()
        val result =
            builds.fail(
                lib,
                "printMagpieNamecoinValue",
                "--relay=wss://relay.example.bit",
                env = builds.signerEnv,
            )
        assertTrue("Namecoin relay hints on .bit endpoints are not supported" in result.output, result.output)
        assertEquals(
            originalEvents,
            builds.relay
                .all()
                .map { it.id }
                .toSet(),
        )
        assertEquals(0, builds.blossom.uploads.get())
    }

    @Test
    fun publisherNamecoinPropertyUsesTheCurrentExactKeyCheck() {
        val lib =
            builds.javaLibrary(
                "lib",
                "1.0.0",
                extraMagpie =
                    """
                    releaseKeyNamecoin.set("releases@example.bit")
                    namecoin { backend.set("disabled") }
                    """.trimIndent(),
            )
        val originalEvents =
            builds.relay
                .all()
                .map { it.id }
                .toSet()
        val result = builds.fail(lib, "publishToNostr", "--check-only", env = builds.signerEnv)
        assertTrue("Namecoin resolution is disabled for releases@example.bit" in result.output, result.output)
        assertEquals(
            originalEvents,
            builds.relay
                .all()
                .map { it.id }
                .toSet(),
        )
        assertEquals(0, builds.blossom.uploads.get())
    }

    @Test
    fun settingsPluginPassesDisabledBackendAndExplicitNamecoinMapping() {
        val app =
            builds.consumer(
                "app",
                "com.example:greeter:1.0.0",
                trust = """namecoin("releases@example.bit")""",
                extraSettings = """namecoin { backend.set("disabled") }""",
            )
        val result = builds.fail(app, "run")
        assertTrue("Namecoin resolution is disabled for releases@example.bit" in result.output, result.output)
    }
}
