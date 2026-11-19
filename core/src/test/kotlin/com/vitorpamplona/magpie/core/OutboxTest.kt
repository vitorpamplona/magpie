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
import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.publish.ListSettings
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.resolve.HttpNip05Resolver
import com.vitorpamplona.magpie.core.resolve.RelayHints
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.InMemoryRelay
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The outbox model: a key's events live on its own relays. Index relays only say where those
 * are (its kind 10002), and a vouching domain's nostr.json can say it too.
 */
class OutboxTest {
    private val world = MagpieWorld()
    private val alice = TestIdentity.ALICE

    /** Alice's own relay; [MagpieWorld.relay] plays the index relay. */
    private val outbox = InMemoryRelay()

    /** nostr.json for example.com, listing alice as releases@ with [nip05Relays]. */
    private var nip05Relays: List<String> = emptyList()
    private val nip05 =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body =
                    if (ex.requestURI.path.startsWith("/example.com/")) {
                        val relays = nip05Relays.joinToString(",") { "\"$it\"" }
                        "{\"names\":{\"releases\":\"${alice.pubKey}\"},\"relays\":{\"${alice.pubKey}\":[$relays]}}"
                    } else {
                        null
                    }
                if (body == null) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    val bytes = body.toByteArray()
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                ex.close()
            }
            start()
        }
    private val nip05Base = "http://127.0.0.1:${nip05.address.port}/{domain}"

    @AfterTest
    fun tearDown() {
        nip05.stop(0)
        outbox.close()
        world.close()
    }

    private fun serverList() = alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_BLOSSOM_SERVERS, arrayOf(arrayOf("server", world.blossom.url)), ""))

    private fun relayList(vararg relays: String) = alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_RELAY_LIST, relays.map { arrayOf("r", it) }.toTypedArray(), ""))

    private fun releasesOn(relay: InMemoryRelay) = relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELEASE))).size

    private val jar = "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"

    @Test
    fun releasesLiveOnThePublishersWriteRelaysAndResolveFromThere() {
        // The index relay knows only where alice's relays are; everything else is on her own relay.
        world.relay.add(relayList(outbox.url))
        outbox.add(serverList(), relayList(outbox.url))
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")

        world.publish(alice, repo.dir, "1.0.0")
        assertEquals(1, releasesOn(outbox), "the release goes to alice's write relay")
        assertEquals(0, releasesOn(world.relay), "not to the index relay")

        world.stack(alice).use { stack ->
            assertContentEquals(File(repo.dir, jar).readBytes(), stack.get(jar).bytes())
        }
    }

    @Test
    fun aDomainsNostrJsonRelaysAreEnoughWithoutIndexRelays() {
        nip05Relays = listOf(outbox.url)
        outbox.add(serverList())
        val repo = FakeMavenRepo(world.dir("repo")).publish("1.0.0")

        val (publisher, _) = world.publisher(alice, keyCheck = ReleaseKeyCheck(HttpNip05Resolver(Http.client(), nip05Base)))
        runBlocking { publisher.publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", indexRelays = emptyList())) }
        assertEquals(1, releasesOn(outbox))

        val config =
            mapOf(
                EngineConfig.INDEX_RELAYS to "",
                EngineConfig.NIP05_BASE_URL to nip05Base,
            )
        world.stack(alice, pin = "domain", extra = config).use { stack ->
            assertContentEquals(File(repo.dir, jar).readBytes(), stack.get(jar).bytes())
        }
    }

    @Test
    fun aPinnedKeyWithNoKnownRelaysFailsWithAHint() {
        world.stack(alice, extra = mapOf(EngineConfig.INDEX_RELAYS to "")).use { stack ->
            assertEquals(502, stack.get(jar).status)
        }
        assertTrue(world.log.lines.any { "No relays known for ${alice.npub}" in it && "indexRelays" in it }, world.log.lines.joinToString("\n"))
    }

    @Test
    fun aDomainsRelaysAreRememberedForDaemonsThatTakeTheKeyFromTheLock() {
        val dir = world.dir("hints")
        RelayHints(dir).put(alice.pubKey, listOf(outbox.url, "https://not-a-relay.example", outbox.url))
        assertEquals(listOf(outbox.url), RelayHints(dir)[alice.pubKey])
    }

    @Test
    fun anEmptyRelayUpdateRevokesMemoryAndDiskHintsAcrossRestart() {
        val dir = world.dir("revoked-hints")
        val hints = RelayHints(dir)
        hints.put(alice.pubKey, listOf(outbox.url))
        val persisted = File(dir, "${alice.pubKey}.txt")
        assertTrue(persisted.readText().isNotBlank())

        hints.put(alice.pubKey, emptyList())

        assertEquals(emptyList(), hints[alice.pubKey])
        assertTrue(persisted.isFile)
        assertEquals("", persisted.readText())
        assertEquals(emptyList(), RelayHints(dir)[alice.pubKey])
    }

    @Test
    fun createRelayListPublishesToTheIndexRelays() {
        val (publisher, _) = world.publisher(alice)
        runBlocking { publisher.createRelayList(listOf(outbox.url), ListSettings(null, listOf(world.relay.url))) }
        val lists = world.relay.query(Filter(kinds = listOf(MagpieSpec.KIND_RELAY_LIST), authors = listOf(alice.pubKey)))
        assertEquals(
            listOf(outbox.url),
            lists
                .single()
                .tags
                .filter { it[0] == "r" }
                .map { it[1] },
        )
        assertEquals(1, outbox.query(Filter(kinds = listOf(MagpieSpec.KIND_RELAY_LIST))).size, "and to the relays it lists")
    }
}
