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
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.BunkerSigner
import com.vitorpamplona.magpie.core.publish.MagpieSigner
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
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
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.InMemoryBlossom
import com.vitorpamplona.magpie.testkit.TestBunker
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    /**
     * A signer that, like a bunker, only knows its key once asked: it notes how many HEAD and
     * BUD-06 checks the server had answered when it was first reached.
     */
    private class RemoteLikeSigner(
        who: TestIdentity,
        private val blossom: InMemoryBlossom,
    ) : MagpieSigner {
        private val real = SecretKeySigner(who.nsec)
        val checksWhenReached = AtomicInteger(-1)
        override val signatures: Int get() = real.signatures

        override suspend fun pubKey(): HexKey {
            checksWhenReached.compareAndSet(-1, blossom.heads.get() + blossom.preflights.get())
            return real.pubKey()
        }

        override suspend fun sign(template: EventTemplate<*>): Event = real.sign(template)
    }

    @Test
    fun aRemoteSignerIsReachedOnlyOnceTheReleaseIsReadyToSign() {
        // The domain names the key, so every check runs before the signer is reached: its
        // connection approval comes right before the signature requests.
        MagpieWorld().use { world ->
            world.setUpPublisher(alice)
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
            val vouches = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
            val signer = RemoteLikeSigner(alice, world.blossom)
            val http = Http.client()
            val late = Publisher(QuartzNostrIO(http, world.log, 3_000), BlossomClient(http, world.log), signer, http, world.log, keyCheck = ReleaseKeyCheck(vouches))
            val result = runBlocking { late.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }

            assertNotNull(result.eventId)
            val checks = world.blossom.heads.get() + world.blossom.preflights.get()
            assertTrue(checks > 0)
            assertEquals(checks, signer.checksWhenReached.get(), "every check ran before the signer was reached")
        }
    }

    @Test
    fun aRemoteSignerWithTheWrongKeyIsRefusedBeforeAnythingIsSignedOrUploaded() {
        MagpieWorld().use { world ->
            world.setUpPublisher(alice)
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
            val vouches = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
            val signer = RemoteLikeSigner(mallory, world.blossom)
            val http = Http.client()
            val publisher = Publisher(QuartzNostrIO(http, world.log, 3_000), BlossomClient(http, world.log), signer, http, world.log, keyCheck = ReleaseKeyCheck(vouches))
            val error =
                assertFailsWith<ReleaseKeyMismatchException> {
                    runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
                }
            assertTrue("releases@example.com" in error.message!! && mallory.npub in error.message!!, error.message)
            assertEquals(0, signer.signatures, "nothing was sent to sign")
            assertEquals(0, world.blossom.uploads.get(), "nothing was uploaded")
            assertEquals(0, world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size)
        }
    }

    private fun latePublisher(
        world: MagpieWorld,
        signer: MagpieSigner,
        vouches: Nip05Resolver,
        nostr: QuartzNostrIO = QuartzNostrIO(Http.client(), world.log, 3_000),
    ): Publisher {
        val http = Http.client()
        return Publisher(nostr, BlossomClient(http, world.log), signer, http, world.log, keyCheck = ReleaseKeyCheck(vouches))
    }

    @Test
    fun aCheckOnlyRunStillProvesTheRemoteSignerIsTheReleaseKey() {
        // A dry run signs nothing, so it connects on purpose: a wrong MAGPIE_BUNKER fails here,
        // not at release time.
        MagpieWorld().use { world ->
            world.setUpPublisher(alice)
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
            val vouches = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
            val settings = PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url), dryRun = true)

            val wrong = RemoteLikeSigner(mallory, world.blossom)
            assertFailsWith<ReleaseKeyMismatchException> { runBlocking { latePublisher(world, wrong, vouches).publish(settings) } }

            val right = RemoteLikeSigner(alice, world.blossom)
            val result = runBlocking { latePublisher(world, right, vouches).publish(settings) }
            assertEquals(0, result.signatures)
            assertTrue(right.checksWhenReached.get() >= 0, "the check run reached the signer")
        }
    }

    @Test
    fun aMissingListingNamesTheRemoteSignersKey() {
        MagpieWorld().use { world ->
            world.setUpPublisher(alice)
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")
            val error =
                assertFailsWith<ReleaseKeyMismatchException> {
                    runBlocking {
                        latePublisher(world, RemoteLikeSigner(alice, world.blossom), Table(mutableMapOf()))
                            .publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url)))
                    }
                }
            assertTrue(alice.pubKey in error.message!!, error.message)
        }
    }

    @Test
    fun aBunkerConnectsOnceAfterEveryCheck() {
        MagpieWorld().use { world ->
            world.setUpPublisher(alice)
            val targets = (1..30).associate { "target$it" to "klib" }
            val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0", targets = targets)
            val vouches = Table(mutableMapOf("releases@example.com" to Nip05Answer.Key(alice.pubKey)))
            TestBunker(world.relay, alice).use { bunker ->
                val checksAtConnect = AtomicInteger(-1)
                bunker.onConnect = { checksAtConnect.compareAndSet(-1, world.blossom.heads.get() + world.blossom.preflights.get()) }
                val nostr = QuartzNostrIO(Http.client(), world.log, 3_000)
                BunkerSigner(bunker.uri, nostr, world.log, timeoutMs = 20_000).use { signer ->
                    val result =
                        runBlocking { latePublisher(world, signer, vouches, nostr).publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(world.relay.url))) }
                    assertNotNull(result.eventId)
                    assertTrue(result.signatures > 2, "several authorizations and the release: ${result.signatures}")
                }
                nostr.close()
                assertEquals(1, bunker.connects.get(), "one connection approval for every request")
                assertEquals(world.blossom.heads.get() + world.blossom.preflights.get(), checksAtConnect.get(), "every check ran before the bunker was reached")
            }
        }
    }

    @Test
    fun everyRunOfAGroupIsTheClientTheBunkerAlreadyPaired() {
        MagpieWorld().use { world ->
            TestBunker(world.relay, alice).use { bunker ->
                // Each run is a fresh process on a fresh CI runner: nothing carries over but the URI.
                fun run(group: String) {
                    val nostr = QuartzNostrIO(Http.client(), world.log, 3_000)
                    BunkerSigner(bunker.uri, nostr, world.log, timeoutMs = 20_000, group = group).use { signer ->
                        val event = runBlocking { signer.sign(EventTemplate<Event>(1L, 1, emptyArray(), group)) }
                        assertEquals(alice.pubKey, event.pubKey)
                    }
                    nostr.close()
                }
                run("com.example")
                run("com.example")
                assertEquals(1, bunker.newClients.get(), "the second release reconnects as the app the user already approved")
                run("com.other")
                assertEquals(2, bunker.newClients.get(), "another group is its own app")
                assertEquals(setOf("Magpie: com.example", "Magpie: com.other"), bunker.clientNames.values.toSet())
            }
        }
    }

    @Test
    fun aBunkerUriPastedWithATrailingNewlineStillConnects() {
        MagpieWorld().use { world ->
            TestBunker(world.relay, alice).use { bunker ->
                val nostr = QuartzNostrIO(Http.client(), world.log, 3_000)
                // CI secrets are often pasted with a newline: the secret sent must be the one the key is derived from.
                BunkerSigner(" ${bunker.uri}\n", nostr, world.log, timeoutMs = 10_000, group = "com.example").use { signer ->
                    assertEquals(alice.pubKey, runBlocking { signer.pubKey() })
                }
                nostr.close()
                assertEquals(1, bunker.newClients.get())
            }
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

    @Test
    fun aFailedDnsLookupIsOnlyAbsenceWhenTheParentDomainResolves() {
        // .invalid never resolves; whether that means "absent" depends on the parent check.
        fun resolver(parentResolves: Boolean) = HttpNip05Resolver(Http.client(), "http://{domain}", resolves = { it == "example.invalid" && parentResolves }, registrableDomain = { "example.invalid" })
        runBlocking {
            assertEquals(Nip05Answer.Absent, resolver(parentResolves = true).lookup("releases@lib.example.invalid"))
            assertIs<Nip05Answer.Unreachable>(resolver(parentResolves = false).lookup("releases@lib.example.invalid"))
        }
    }

    @Test
    fun behindAProxyAMissingSubdomainIsStillAbsent() {
        // A proxy reports a missing host as a failed connection, not UnknownHostException: here
        // every request goes to a closed port, and only DNS can tell the cases apart.
        val closed = java.net.ServerSocket(0).use { it.localPort }

        fun resolver(subdomainResolves: Boolean) =
            HttpNip05Resolver(
                Http.client(),
                "http://127.0.0.1:$closed/{domain}",
                resolves = { it == "example.com" || subdomainResolves },
                registrableDomain = { "example.com" },
            )
        runBlocking {
            assertEquals(Nip05Answer.Absent, resolver(subdomainResolves = false).lookup("releases@lib.example.com"))
            assertIs<Nip05Answer.Unreachable>(resolver(subdomainResolves = true).lookup("releases@lib.example.com"), "a subdomain that exists but can't be read")
            assertIs<Nip05Answer.Unreachable>(resolver(subdomainResolves = false).lookup("releases@example.com"), "the registrable domain itself never counts as missing")
        }
    }

    @Test
    fun oversizedNostrJsonIsRefused() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val body = ("{\"names\":{\"releases\":\"${alice.pubKey}\"},\"pad\":\"" + "x".repeat(100_000) + "\"}").toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val answer = runBlocking { HttpNip05Resolver(Http.client(), "http://127.0.0.1:${server.address.port}/{domain}").lookup("releases@example.com") }
            assertIs<Nip05Answer.Unreachable>(answer)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aFailedLookupIsRememberedBriefly() {
        val table = Table(mutableMapOf())
        val policy = policy(table)
        assertFailsWith<MagpieSecurityException> { publisherOf(policy) }
        assertFailsWith<MagpieSecurityException> { publisherOf(policy) }
        assertEquals(2, table.asked.size, "the second request reused the failure instead of walking again")
    }
}
