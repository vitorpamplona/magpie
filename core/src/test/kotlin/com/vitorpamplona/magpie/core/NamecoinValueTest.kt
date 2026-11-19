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
package com.vitorpamplona.magpie.core

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.bridge.EngineLogger
import com.vitorpamplona.magpie.core.engine.MagpieEngineImpl
import com.vitorpamplona.magpie.core.publish.NamecoinValue
import com.vitorpamplona.magpie.core.resolve.RelayUrls
import com.vitorpamplona.magpie.testkit.TestIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NamecoinValueTest {
    private val alice = TestIdentity.ALICE.pubKey

    @Test
    fun deterministicCompactJsonUsesOneExactKeyEverywhere() {
        val expected =
            """{"nostr":{"names":{"releases":"$alice"},"relays":{"$alice":["wss://one.example.com/","wss://two.example.com/nostr"]}}}"""
        val rendered =
            NamecoinValue.render(
                alice,
                listOf(" wss://one.example.com ", "wss://one.example.com", "wss://two.example.com/nostr"),
            )
        assertEquals(expected, rendered)
        assertEquals(expected, NamecoinValue.render(alice, listOf("wss://one.example.com", "wss://two.example.com/nostr")))
        assertEquals("""{"nostr":{"names":{"releases":"$alice"}}}""", NamecoinValue.render(alice))
    }

    @Test
    fun invalidBitAndExcessRelayHintsAreRejectedRatherThanDropped() {
        assertFailsWith<IllegalArgumentException> { NamecoinValue.render(alice, listOf("https://example.com")) }
        assertFailsWith<IllegalArgumentException> { NamecoinValue.render(alice, listOf("wss://relay.example.bit")) }
        val tooMany = (1..(RelayUrls.MAX_PER_SOURCE + 1)).map { "wss://relay$it.example.com" }
        val error = assertFailsWith<IllegalArgumentException> { NamecoinValue.render(alice, tooMany) }
        assertTrue("at most" in error.message!!, error.message)
    }

    @Test
    fun theBudgetIsExactly520Utf8Bytes() {
        val base = NamecoinValue.render(alice, listOf("wss://example.com/")).toByteArray(Charsets.UTF_8).size
        val extra = NamecoinValue.MAX_VALUE_BYTES - base
        assertTrue(extra > 0)
        val atLimit = NamecoinValue.render(alice, listOf("wss://example.com/${"a".repeat(extra)}"))
        assertEquals(NamecoinValue.MAX_VALUE_BYTES, atLimit.toByteArray(Charsets.UTF_8).size)

        val error =
            assertFailsWith<IllegalArgumentException> {
                NamecoinValue.render(alice, listOf("wss://example.com/${"a".repeat(extra + 1)}"))
            }
        assertTrue("521" in error.message!! && "nothing was dropped" in error.message!!, error.message)
    }

    @Test
    fun enginePrintTaskUsesTheSelectedSignerWithoutPublishing() {
        val logger =
            object : EngineLogger {
                override fun error(message: String) {}

                override fun warn(message: String) {}

                override fun lifecycle(message: String) {}

                override fun info(message: String) {}

                override fun debug(message: String) {}
            }
        val result =
            MagpieEngineImpl().runTask(
                EngineConfig.TASK_PRINT_NAMECOIN_VALUE,
                mapOf(
                    EngineConfig.NSEC to TestIdentity.ALICE.nsec,
                    EngineConfig.RELEASE_KEY_CHECK to EngineConfig.RELEASE_KEY_OFF,
                    EngineConfig.NAMECOIN_RELAYS to "wss://one.example.com\nwss://two.example.com/nostr",
                ),
                logger,
            )
        val expected = NamecoinValue.render(alice, listOf("wss://one.example.com", "wss://two.example.com/nostr"))
        assertEquals(expected, result.getValue("namecoinValue"))
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toString(), result.getValue("namecoinBytes"))
        assertEquals(setOf("namecoinValue", "namecoinBytes"), result.keys)
    }

    @Test
    fun malformedKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { NamecoinValue.render("not-a-key") }
        assertFailsWith<IllegalArgumentException> { NamecoinValue.render("g".repeat(64)) }
    }
}
