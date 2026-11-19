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
import com.vitorpamplona.magpie.core.engine.ResolverStack
import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.resolve.Nip05Answer
import com.vitorpamplona.magpie.core.resolve.Nip05ResolverFactory
import com.vitorpamplona.magpie.core.resolve.parseElectrumxServers
import com.vitorpamplona.magpie.testkit.TestIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NamecoinFactoryTest {
    private val alice = TestIdentity.ALICE.pubKey
    private val dir = Files.createTempDirectory("magpie-namecoin-factory").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun rpcServer(handler: (String) -> Pair<Int, String>): Pair<HttpServer, String> {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val request = exchange.requestBody.readBytes().decodeToString()
                    val (status, response) = handler(request)
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }
        return server to "http://127.0.0.1:${server.address.port}/"
    }

    private fun config(url: String): Map<String, String> =
        mapOf(
            EngineConfig.NAMECOIN_BACKEND to EngineConfig.NAMECOIN_BACKEND_CORE_RPC,
            EngineConfig.NAMECOIN_CORE_RPC_URL to url,
            EngineConfig.NAMECOIN_LOOKUP_TIMEOUT_MS to "2000",
            EngineConfig.NAMECOIN_CORE_RPC_USERNAME_ENV to "TEST_NMC_USER",
            EngineConfig.NAMECOIN_CORE_RPC_PASSWORD_ENV to "TEST_NMC_PASSWORD",
        )

    @Test
    fun coreRpcResolutionUsesEnvironmentCredentialsWithoutPuttingThemInConfig() {
        var authorization: String? = null
        val record = """{"nostr":{"names":{"releases":"$alice"}}}"""
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                    val request = exchange.requestBody.readBytes().decodeToString()
                    assertTrue("name_show" in request && "d/example" in request, request)
                    val response =
                        """{"result":{"name":"d/example","value":${JsonPrimitive(record)},"expired":false},"error":null}"""
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}/"
        try {
            val config = config(url)
            val environment = mapOf("TEST_NMC_USER" to "test-user", "TEST_NMC_PASSWORD" to "test-password")
            assertFalse(config.values.any { it in environment.values })
            val http = Http.client()
            try {
                val answer = runBlocking { Nip05ResolverFactory.create(http, config, environment::get).lookup("releases@example.bit") }
                assertEquals(Nip05Answer.Key(alice), answer)
                assertEquals(Credentials.basic("test-user", "test-password"), authorization)
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun consumerAndPublisherUseEquivalentCoreResolution() {
        val record = """{"nostr":{"names":{"releases":"$alice"}}}"""
        val (server, url) =
            rpcServer {
                200 to """{"result":{"name":"d/example","value":${JsonPrimitive(record)},"expired":false},"error":null}"""
            }
        try {
            val config = config(url)
            val stackConfig =
                config +
                    mapOf(
                        EngineConfig.GROUPS to "bit.example=domain",
                        EngineConfig.CACHE_DIR to File(dir, "cache").path,
                        EngineConfig.LOCK_FILE to File(dir, "magpie.lock").path,
                        EngineConfig.INDEX_RELAYS to "",
                    )
            ResolverStack.create(stackConfig, RecordingLog()).use { stack ->
                assertEquals(alice, runBlocking { stack.resolver.publisherFor("bit.example") }!!.pubKey)
            }

            val http = Http.client()
            try {
                val publisherCheck = ReleaseKeyCheck(Nip05ResolverFactory.create(http, config) { null })
                assertEquals(emptyList(), runBlocking { publisherCheck.verify("bit.example", alice) })
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
            val locked =
                com.vitorpamplona.magpie.spec.LockFile
                    .parse(File(dir, "magpie.lock").readText())
            assertEquals("namecoin:releases@example.bit", locked.publishers.getValue("bit.example").source)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun coreRpcAbsenceFailuresAndExpiryKeepTheirSemantics() {
        val (server, url) =
            rpcServer { request ->
                when {
                    "d/missing" in request -> 200 to """{"result":null,"error":{"code":-4,"message":"name not found"}}"""
                    "d/expired" in request ->
                        200 to
                            """{"result":{"name":"d/expired","value":${JsonPrimitive("{}")},"expired":true},"error":null}"""
                    "d/broken" in request ->
                        200 to
                            """{"result":{"name":"d/broken","value":${JsonPrimitive("{\"nostr\":")},"expired":false},"error":null}"""
                    else -> 500 to ""
                }
            }
        try {
            val http = Http.client()
            try {
                val resolver = Nip05ResolverFactory.create(http, config(url)) { null }
                runBlocking {
                    assertEquals(Nip05Answer.Absent, resolver.lookup("releases@missing.bit"))
                    assertEquals(Nip05Answer.Absent, resolver.lookup("releases@expired.bit"))
                    assertIs<Nip05Answer.Unreachable>(resolver.lookup("releases@broken.bit"))
                    assertIs<Nip05Answer.Unreachable>(resolver.lookup("releases@unreachable.bit"))
                }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun electrumxEndpointsAreExplicitAndRpcUrlsCannotContainCredentials() {
        val servers = parseElectrumxServers(listOf("ssl://one.example:50002", "tcp://two.example:50001", "three.example:50002"))
        assertEquals(listOf(true, false, true), servers.map { it.useSsl })
        assertEquals(listOf("one.example", "two.example", "three.example"), servers.map { it.host })
        assertFailsWith<IllegalArgumentException> { parseElectrumxServers(listOf("https://one.example:50002")) }
        assertFailsWith<IllegalArgumentException> { parseElectrumxServers(listOf("ssl://one.example")) }

        val http = Http.client()
        try {
            assertFailsWith<IllegalArgumentException> {
                Nip05ResolverFactory.create(http, config("http://user:password@127.0.0.1:8336/")) { null }
            }
            assertFailsWith<IllegalArgumentException> {
                Nip05ResolverFactory.create(
                    http,
                    mapOf(EngineConfig.NAMECOIN_BACKEND to EngineConfig.NAMECOIN_BACKEND_ELECTRUMX),
                ) { null }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
