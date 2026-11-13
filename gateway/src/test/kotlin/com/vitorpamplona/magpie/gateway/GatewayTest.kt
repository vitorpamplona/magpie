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
package com.vitorpamplona.magpie.gateway

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.engine.ResolverStack
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.core.resolve.LocalRepoServer
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.FakeMavenRepo
import com.vitorpamplona.magpie.testkit.InMemoryBlossom
import com.vitorpamplona.magpie.testkit.InMemoryRelay
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayTest {
    private val relay = InMemoryRelay()
    private val blossom = InMemoryBlossom()
    private val root = Files.createTempDirectory("magpie-gw").toFile()
    private val alice = TestIdentity.ALICE
    private val http = OkHttpClient()

    @AfterTest
    fun tearDown() {
        relay.close()
        blossom.close()
        root.deleteRecursively()
    }

    private fun publish(): FakeMavenRepo {
        relay.add(
            alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_BLOSSOM_SERVERS, arrayOf(arrayOf("server", blossom.url)), "")),
            alice.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_RELAY_LIST, arrayOf(arrayOf("r", relay.url)), "")),
        )
        val repo = FakeMavenRepo(File(root, "repo")).publish("1.0.0")
        val client = Http.client()
        val nostr = QuartzNostrIO(client, MagpieLog.NONE, idleTimeoutMs = 3000)
        runBlocking {
            Publisher(nostr, BlossomClient(client, MagpieLog.NONE), SecretKeySigner(alice.nsec), client, MagpieLog.NONE)
                .publish(PublishSettings(repo.dir, "com.example", "1.0.0", "lib", listOf(relay.url)))
        }
        nostr.close()
        return repo
    }

    private fun get(url: String): Pair<Int, ByteArray> = http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.bytes() }

    @Test
    fun servesTheSameBytesAsTheLocalResolver() {
        val repo = publish()
        val port = ServerSocket(0).use { it.localPort }
        val config = GatewayConfig(listOf(relay.url), mapOf("com.example" to alice.npub), cacheDir = File(root, "gw-cache"), host = "127.0.0.1", port = port)
        val (server, stack) = startGateway(config, wait = false)
        val local =
            ResolverStack.create(
                mapOf(
                    EngineConfig.RELAYS to relay.url,
                    EngineConfig.GROUPS to "com.example=${alice.npub}",
                    EngineConfig.CACHE_DIR to File(root, "local-cache").path,
                ),
                MagpieLog.NONE,
            )
        val localServer = LocalRepoServer(local.router, MagpieLog.NONE)
        try {
            val paths =
                listOf(
                    "com/example/lib/maven-metadata.xml",
                    "com/example/lib/maven-metadata.xml.sha1",
                    "com/example/lib/1.0.0/lib-1.0.0.module",
                    "com/example/lib/1.0.0/lib-1.0.0.pom.sha512",
                    "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar",
                    "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar.md5",
                    "com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib.sha256",
                )
            paths.forEach { path ->
                val (gwStatus, gwBody) = get("http://127.0.0.1:$port/$path")
                val (localStatus, localBody) = get(localServer.url + path)
                assertEquals(200, gwStatus, path)
                assertEquals(200, localStatus, path)
                assertContentEquals(localBody, gwBody, path)
            }
            assertContentEquals(File(repo.dir, "com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").readBytes(), get("http://127.0.0.1:$port/com/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar").second)
            assertEquals(404, get("http://127.0.0.1:$port/org/untrusted/x/1.0/x-1.0.pom").first)
            assertEquals(200, get("http://127.0.0.1:$port/healthz").first)
        } finally {
            localServer.close()
            local.close()
            server.stop(0, 500)
            stack.close()
        }
    }

    @Test
    fun configComesFromTheEnvironmentAndRefusesNip05() {
        val config =
            GatewayConfig.load(
                mapOf(
                    "MAGPIE_RELAYS" to "wss://a.example, wss://b.example",
                    "MAGPIE_TRUST" to "com.example=${alice.npub},org.other=${alice.pubKey}",
                    "PORT" to "9090",
                ),
            )
        assertEquals(listOf("wss://a.example", "wss://b.example"), config.relays)
        assertEquals(setOf("com.example", "org.other"), config.trust.keys)
        assertEquals(9090, config.port)
        assertFailsWith<IllegalArgumentException> {
            GatewayConfig.load(mapOf("MAGPIE_RELAYS" to "wss://a", "MAGPIE_TRUST" to "com.example=nip05:alice@example.com"))
        }
    }
}
