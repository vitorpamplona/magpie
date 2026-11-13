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

import com.sun.net.httpserver.HttpServer
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.publish.ReleaseKeyMismatchException
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.HttpNip05Resolver
import com.vitorpamplona.magpie.core.resolve.LockStore
import com.vitorpamplona.magpie.core.resolve.MagpieSecurityException
import com.vitorpamplona.magpie.core.resolve.Nip05Answer
import com.vitorpamplona.magpie.core.resolve.Nip05Resolver
import com.vitorpamplona.magpie.core.resolve.PublicSuffix
import com.vitorpamplona.magpie.core.resolve.TrustPolicy
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.LockedPublisher
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.TestIdentity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The group's domain vouches for its release key: `releases@<domain>` down the domain chain. */
class ReleaseKeyTest {
    private val alice = TestIdentity.ALICE
    private val mallory = TestIdentity.MALLORY
    private val dir = Files.createTempDirectory("magpie-key").toFile()
    private val lockFile = File(dir, "magpie.lock")
    private val log = RecordingLog()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Answers from a table; anything not in it is absent. */
    private class Table(
        val answers: MutableMap<String, Nip05Answer>,
    ) : Nip05Resolver {
        val asked = mutableListOf<String>()

        override suspend fun lookup(identifier: String): Nip05Answer {
            asked += identifier
            return answers[identifier] ?: Nip05Answer.Absent
        }
    }

    private fun policy(
        table: Table,
        pin: String = "domain",
        group: String = "com.example.lib",
        offline: Boolean = false,
    ) = TrustPolicy(listOf(GroupRule.parse("$group=$pin")), LockStore(lockFile, frozen = false, log = log), table, offline, log)

    private fun publisherOf(
        policy: TrustPolicy,
        group: String = "com.example.lib",
    ) = runBlocking { policy.publisherFor(group)!!.pubKey }

    @Test
    fun theRegistrableDomainCoversTheGroupWhenTheSubdomainListsNothing() {
        val table = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
        assertEquals(alice.pubKey, publisherOf(policy(table)))
        assertEquals(listOf("releases@lib.example.com", "releases@example.com"), table.asked)
        val locked = LockFile.parse(lockFile.readText()).publishers.getValue("com.example.lib")
        assertEquals(LockedPublisher(alice.pubKey, "nip05:releases@example.com"), locked)
    }

    @Test
    fun anUnreachableSubdomainDoesNotFallThroughToTheParent() {
        val table =
            Table(
                mutableMapOf(
                    "releases@lib.example.com" to Nip05Answer.Unreachable("timeout"),
                    "releases@example.com" to Nip05Answer.Key(mallory.pubKey),
                ),
            )
        val error = assertFailsWith<MagpieSecurityException> { publisherOf(policy(table)) }
        assertTrue("releases@lib.example.com" in error.message!!, error.message)

        // With a lock, the pinned key carries the build through the outage.
        lockFile.writeText(LockFile().withPublisher("com.example.lib", LockedPublisher(alice.pubKey, "nip05:releases@lib.example.com")).render())
        assertEquals(alice.pubKey, publisherOf(policy(table)))
        assertTrue(log.lines.any { it.startsWith("WARN") && "releases@lib.example.com" in it })
    }

    @Test
    fun aSubdomainThatStartsListingAnotherKeyFailsLoudly() {
        val table = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
        publisherOf(policy(table))
        table.answers["releases@lib.example.com"] = Nip05Answer.Key(mallory.pubKey)
        val error = assertFailsWith<MagpieSecurityException> { publisherOf(policy(table)) }
        assertTrue("PUBLISHER KEY CHANGED" in error.message!!)

        // The same key, now listed by the subdomain, is fine: the lock just records the new source.
        table.answers["releases@lib.example.com"] = Nip05Answer.Key(alice.pubKey)
        assertEquals(alice.pubKey, publisherOf(policy(table)))
        assertEquals(
            "nip05:releases@lib.example.com",
            LockFile
                .parse(lockFile.readText())
                .publishers
                .getValue("com.example.lib")
                .source,
        )
    }

    @Test
    fun noListedKeyFailsWithInstructions() {
        val error = assertFailsWith<MagpieSecurityException> { publisherOf(policy(Table(mutableMapOf()))) }
        assertTrue("releases" in error.message!! && "nostr.json" in error.message!!, error.message)
    }

    @Test
    fun offlineUsesOnlyTheLock() {
        assertFailsWith<MagpieSecurityException> { publisherOf(policy(Table(mutableMapOf()), offline = true)) }
        lockFile.writeText(LockFile().withPublisher("com.example.lib", LockedPublisher(alice.pubKey, "nip05:releases@example.com")).render())
        val table = Table(mutableMapOf())
        assertEquals(alice.pubKey, publisherOf(policy(table, offline = true)))
        assertEquals(emptyList(), table.asked)
    }

    @Test
    fun configuredIdentitiesMustBelongToTheGroup() {
        assertFailsWith<IllegalArgumentException> { policy(Table(mutableMapOf()), pin = "nip05:releases@evil.com") }
        assertFailsWith<IllegalArgumentException> { policy(Table(mutableMapOf()), pin = "nip05:releases@com") }
        val table = Table(mutableMapOf("maven@example.com" to Nip05Answer.Key(alice.pubKey)))
        assertEquals(alice.pubKey, publisherOf(policy(table, pin = "nip05:maven@example.com")))
        // A group no domain can vouch for must pin a key.
        assertFailsWith<IllegalArgumentException> { policy(Table(mutableMapOf()), group = "com.my_company") }
    }

    @Test
    fun thePublicSuffixListComesFromOkHttp() {
        assertEquals("vitorpamplona.com", PublicSuffix.registrableDomain("quartz.vitorpamplona.com"))
        assertEquals("someone.github.io", PublicSuffix.registrableDomain("someone.github.io"))
        assertEquals("example.co.uk", PublicSuffix.registrableDomain("lib.example.co.uk"))
        assertEquals(null, PublicSuffix.registrableDomain("github.io"))
    }

    @Test
    fun httpLookupsOnlyCallADefiniteNoAbsent() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val domain = ex.requestURI.path.split('/')[1]
                    val (status, body) =
                        when (domain) {
                            "listed.test" -> 200 to """{"names":{"releases":"${alice.pubKey}"}}"""
                            "other-name.test" -> 200 to """{"names":{"bob":"${alice.pubKey}"}}"""
                            "broken.test" -> 500 to "oops"
                            "garbage.test" -> 200 to "<html>"
                            "redirect.test" -> 302 to ""
                            else -> 404 to ""
                        }
                    if (status == 302) ex.responseHeaders.add("Location", "http://127.0.0.1:1/")
                    val bytes = body.toByteArray()
                    ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
                    ex.close()
                }
                start()
            }
        try {
            val nip05 = HttpNip05Resolver(Http.client(), "http://127.0.0.1:${server.address.port}/{domain}")
            runBlocking {
                assertEquals(Nip05Answer.Key(alice.pubKey), nip05.lookup("releases@listed.test"))
                assertEquals(Nip05Answer.Absent, nip05.lookup("releases@other-name.test"))
                assertEquals(Nip05Answer.Absent, nip05.lookup("releases@missing.test"))
                assertIs<Nip05Answer.Unreachable>(nip05.lookup("releases@broken.test"))
                assertIs<Nip05Answer.Unreachable>(nip05.lookup("releases@garbage.test"))
                assertIs<Nip05Answer.Unreachable>(nip05.lookup("releases@redirect.test"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun publishingRefusesAKeyTheDomainDoesNotVouchFor() {
        MagpieWorld().use { world ->
            world.setUpPublisher(mallory)
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
            val vouches = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
            val signer = SecretKeySigner(mallory.nsec)
            val (publisher, _) = world.publisher(mallory, signer, keyCheck = ReleaseKeyCheck(vouches))
            val error =
                assertFailsWith<ReleaseKeyMismatchException> {
                    runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
                }
            assertTrue("releases@example.com" in error.message!!, error.message)
            assertEquals(0, signer.signatures, "nothing signed")

            // The vouched key publishes.
            world.setUpPublisher(alice)
            val (ok, aliceSigner) = world.publisher(alice, keyCheck = ReleaseKeyCheck(vouches))
            runBlocking { ok.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
            assertEquals(2, aliceSigner.signatures)

            // No listing at all: the error says exactly what to add where.
            val none = assertFailsWith<ReleaseKeyMismatchException> { runBlocking { ReleaseKeyCheck(Table(mutableMapOf())).verify("com.example", alice.pubKey) } }
            assertTrue("https://example.com/.well-known/nostr.json" in none.message!! && alice.pubKey in none.message!!, none.message)
        }
    }
}
