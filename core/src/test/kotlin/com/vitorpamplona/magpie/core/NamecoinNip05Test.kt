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

import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.publish.ReleaseKeyMismatchException
import com.vitorpamplona.magpie.core.resolve.BoundedNamecoinClient
import com.vitorpamplona.magpie.core.resolve.DispatchingNip05Resolver
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.LockStore
import com.vitorpamplona.magpie.core.resolve.MagpieSecurityException
import com.vitorpamplona.magpie.core.resolve.NamecoinNip05Resolver
import com.vitorpamplona.magpie.core.resolve.Nip05Answer
import com.vitorpamplona.magpie.core.resolve.Nip05Resolver
import com.vitorpamplona.magpie.core.resolve.RelayUrls
import com.vitorpamplona.magpie.core.resolve.TrustPolicy
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.IElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NameShowResult
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinLookupException
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolutionLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NamecoinNip05Test {
    private val alice = TestIdentity.ALICE.pubKey
    private val bob = TestIdentity.MALLORY.pubKey

    private class FakeElectrumX : IElectrumXClient {
        val queried = mutableListOf<String>()
        var lookup: suspend (String) -> NameShowResult? = { null }

        fun register(
            name: String,
            value: String,
        ) {
            val previous = lookup
            lookup = { requested ->
                if (requested == name) NameShowResult(name, value) else previous(requested)
            }
        }

        override suspend fun nameShowWithFallback(
            identifier: String,
            servers: List<ElectrumxServer>,
        ): NameShowResult? {
            queried += identifier
            return lookup(identifier)
        }
    }

    private fun resolver(client: FakeElectrumX): NamecoinNip05Resolver =
        NamecoinNip05Resolver(
            NamecoinNameResolver(BoundedNamecoinClient(client), lookupTimeoutMs = 20_000L) { emptyList() },
        )

    @Test
    fun exactReleasesEntryWinsAndRootFallbackIsUndecided() =
        runTest {
            val exact = FakeElectrumX()
            exact.register("d/example", """{"nostr":{"names":{"releases":"$alice","_":"$bob"}}}""")
            assertEquals(Nip05Answer.Key(alice), resolver(exact).lookup("releases@example.bit"))

            val root = FakeElectrumX()
            root.register("d/example", """{"nostr":{"names":{"_":"$bob"}}}""")
            val answer = resolver(root).lookup("releases@example.bit")
            assertIs<Nip05Answer.Unreachable>(answer)
            assertTrue("exact" in answer.reason && "_" in answer.reason, answer.reason)
        }

    @Test
    fun missingNameIsAbsentButAmbiguousExistingRecordsAreUndecided() =
        runTest {
            val missing = FakeElectrumX()
            missing.lookup = { name -> throw NamecoinLookupException.NameNotFound(name) }
            assertEquals(Nip05Answer.Absent, resolver(missing).lookup("releases@example.bit"))

            val expired = FakeElectrumX()
            expired.lookup = { name -> throw NamecoinLookupException.NameExpired(name) }
            assertEquals(Nip05Answer.Absent, resolver(expired).lookup("releases@example.bit"))

            val noExact = FakeElectrumX()
            noExact.register("d/example", """{"nostr":{"names":{"alice":"$alice"}}}""")
            assertIs<Nip05Answer.Unreachable>(resolver(noExact).lookup("releases@example.bit"))

            val noMap = FakeElectrumX()
            noMap.register("d/example", """{"nostr":{"names":{"releases":"$alice"}}}""")
            assertIs<Nip05Answer.Unreachable>(resolver(noMap).lookup("releases@quartz.example.bit"))
        }

    @Test
    fun malformedChildEntriesNeverAuthorizeAValidParent() {
        val children =
            linkedMapOf(
                "invalid hex" to """{"nostr":{"names":{"releases":"not-a-key"}}}""",
                "object entry" to """{"nostr":{"names":{"releases":{"hex":"$alice"}}}}""",
                "numeric entry" to """{"nostr":{"names":{"releases":42}}}""",
                "array names" to """{"nostr":{"names":["$alice"]}}""",
                "string names" to """{"nostr":{"names":"$alice"}}""",
                "array nostr" to """{"nostr":["$alice"]}""",
            )
        children.forEach { (label, child) ->
            val client = FakeElectrumX()
            client.register(
                "d/example",
                """{"map":{"quartz":$child},"nostr":{"names":{"releases":"$alice"}}}""",
            )
            val adapted = resolver(client)
            val asked = mutableListOf<String>()
            val recording =
                Nip05Resolver { identifier ->
                    asked += identifier
                    adapted.lookup(identifier)
                }
            val caseDir = Files.createTempDirectory("magpie-namecoin-malformed").toFile()
            val lockFile = File(caseDir, "magpie.lock")
            try {
                val policy =
                    TrustPolicy(
                        listOf(GroupRule.parse("bit.example.quartz=domain")),
                        LockStore(lockFile, false, RecordingLog()),
                        recording,
                        false,
                        RecordingLog(),
                    )
                assertFailsWith<MagpieSecurityException>(label) {
                    runBlocking { policy.publisherFor("bit.example.quartz") }
                }
                assertEquals(listOf("releases@quartz.example.bit"), asked, "$label consumer parent lookup")
                assertFalse(lockFile.exists(), "$label consumer wrote a lock")

                asked.clear()
                assertFailsWith<ReleaseKeyMismatchException>(label) {
                    runBlocking { ReleaseKeyCheck(recording).verify("bit.example.quartz", alice) }
                }
                assertEquals(listOf("releases@quartz.example.bit"), asked, "$label publisher parent lookup")
                assertFalse(lockFile.exists(), "$label publisher wrote a lock")
            } finally {
                caseDir.deleteRecursively()
            }
        }
    }

    @Test
    fun failedImportsDelegatesAndMalformedMapsAreUnreachable() =
        runTest {
            val imported = FakeElectrumX()
            imported.register("d/example", """{"import":"dd/missing","nostr":{"names":{"releases":"$alice"}}}""")
            assertIs<Nip05Answer.Unreachable>(resolver(imported).lookup("releases@example.bit"))

            val delegated = FakeElectrumX()
            delegated.register("d/example", """{"delegate":"dd/missing","nostr":{"names":{"releases":"$alice"}}}""")
            assertIs<Nip05Answer.Unreachable>(resolver(delegated).lookup("releases@example.bit"))

            val mapped = FakeElectrumX()
            mapped.register("d/example", """{"map":{"quartz":42},"nostr":{"names":{"releases":"$alice"}}}""")
            assertIs<Nip05Answer.Unreachable>(resolver(mapped).lookup("releases@quartz.example.bit"))

            val malformed = FakeElectrumX()
            malformed.register("d/example", """{"nostr":""")
            assertIs<Nip05Answer.Unreachable>(resolver(malformed).lookup("releases@example.bit"))
        }

    @Test
    fun traversalCyclesAndLimitsAreUnreachable() =
        runTest {
            val cycle = FakeElectrumX()
            cycle.register("d/example", """{"import":"dd/loop"}""")
            cycle.register("dd/loop", """{"import":"d/example"}""")
            assertIs<Nip05Answer.Unreachable>(resolver(cycle).lookup("releases@example.bit"))

            val deep = FakeElectrumX()
            val root = NamecoinNameResolver(BoundedNamecoinClient(deep), lookupTimeoutMs = 20_000L) { emptyList() }
            deep.register("d/example", """{"map":{"quartz":{"map":{"nested":{"nostr":{"names":{"releases":"$alice"}}}}}}}""")
            assertIs<Nip05Answer.Key>(NamecoinNip05Resolver(root).lookup("releases@nested.quartz.example.bit"))

            val limited =
                NamecoinNameResolver(
                    BoundedNamecoinClient(deep),
                    lookupTimeoutMs = 20_000L,
                    resolutionLimits = NamecoinResolutionLimits(maxDepth = 1),
                ) { emptyList() }
            assertIs<Nip05Answer.Unreachable>(NamecoinNip05Resolver(limited).lookup("releases@nested.quartz.example.bit"))
        }

    @Test
    fun relayHintsAreCleanedBoundedAndExcludeBitEndpoints() =
        runTest {
            val relays =
                buildList {
                    add("https://not-a-relay.example.com")
                    add("wss://relay0.example.com")
                    add("wss://relay0.example.com")
                    add("wss://relay.example.bit")
                    repeat(10) { add("wss://relay${it + 1}.example.com") }
                }
            val relayJson = relays.joinToString(",") { "\"$it\"" }
            val client = FakeElectrumX()
            client.register(
                "d/example",
                """{"nostr":{"names":{"releases":"$alice"},"relays":{"$alice":[$relayJson]}}}""",
            )

            val answer = resolver(client).lookup("releases@example.bit")
            assertIs<Nip05Answer.Key>(answer)
            assertEquals(RelayUrls.MAX_PER_SOURCE, answer.relays.size)
            assertEquals("wss://relay0.example.com/", answer.relays.first())
            assertTrue(answer.relays.none { ".bit" in it })
            assertEquals(answer.relays.size, answer.relays.distinct().size)
        }

    @Test
    fun oversizedRecordsAndBackendFailuresDoNotBecomeAbsence() =
        runTest {
            val oversized = FakeElectrumX()
            oversized.register("d/example", """{"pad":"${"x".repeat(1_024)}"}""")
            assertIs<Nip05Answer.Unreachable>(resolver(oversized).lookup("releases@example.bit"))

            val unreachable = FakeElectrumX()
            unreachable.lookup = { throw NamecoinLookupException.ServersUnreachable() }
            assertIs<Nip05Answer.Unreachable>(resolver(unreachable).lookup("releases@example.bit"))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationPropagates() =
        runTest {
            val client = FakeElectrumX()
            client.lookup = { awaitCancellation() }
            val pending = async { resolver(client).lookup("releases@example.bit") }
            runCurrent()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
        }

    @Test
    fun inconclusiveResultsAreNotCachedAsAbsent() =
        runTest {
            val client = FakeElectrumX()
            var attempts = 0
            client.lookup = { name ->
                attempts++
                if (attempts == 1) {
                    throw NamecoinLookupException.ServersUnreachable()
                } else {
                    NameShowResult(name, """{"nostr":{"names":{"releases":"$alice"}}}""")
                }
            }
            val resolver = resolver(client)
            assertIs<Nip05Answer.Unreachable>(resolver.lookup("releases@example.bit"))
            assertEquals(Nip05Answer.Key(alice), resolver.lookup("releases@example.bit"))
            assertEquals(2, attempts)
        }

    @Test
    fun ordinaryIdentifiersAlwaysUseHttpsAndDisabledNamecoinFailsClosed() =
        runTest {
            val httpsAsked = mutableListOf<String>()
            val namecoinAsked = mutableListOf<String>()
            val https =
                Nip05Resolver { identifier ->
                    httpsAsked += identifier
                    Nip05Answer.Key(alice)
                }
            val namecoin =
                Nip05Resolver { identifier ->
                    namecoinAsked += identifier
                    Nip05Answer.Key(bob)
                }
            val dispatching = DispatchingNip05Resolver(https, namecoin)
            assertEquals(Nip05Answer.Key(alice), dispatching.lookup("releases@example.com"))
            assertEquals(Nip05Answer.Key(bob), dispatching.lookup("releases@example.bit"))
            assertEquals(listOf("releases@example.com"), httpsAsked)
            assertEquals(listOf("releases@example.bit"), namecoinAsked)

            val disabled = DispatchingNip05Resolver(https, null)
            assertIs<Nip05Answer.Unreachable>(disabled.lookup("releases@example.bit"))
            assertEquals(listOf("releases@example.com"), httpsAsked)
        }
}
