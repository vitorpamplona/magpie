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
import com.vitorpamplona.magpie.core.publish.ReleaseKeyMismatchException
import com.vitorpamplona.magpie.core.resolve.CoreRpcEnvelopeGuard
import com.vitorpamplona.magpie.core.resolve.CoreRpcNamecoinClient
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.LockStore
import com.vitorpamplona.magpie.core.resolve.MagpieSecurityException
import com.vitorpamplona.magpie.core.resolve.MalformedCoreRpcResponseException
import com.vitorpamplona.magpie.core.resolve.Nip05Answer
import com.vitorpamplona.magpie.core.resolve.Nip05Resolver
import com.vitorpamplona.magpie.core.resolve.Nip05ResolverFactory
import com.vitorpamplona.magpie.core.resolve.TrustPolicy
import com.vitorpamplona.magpie.core.resolve.parseElectrumxServers
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.IElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NameShowResult
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinCoreRpcClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinCoreRpcConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
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
    fun malformedSuccessfulCoreResponsesNeverAuthorizeAValidParent() {
        val malformed =
            linkedMapOf(
                "missing result" to """{"error":null}""",
                "null result" to """{"result":null,"error":null}""",
                "scalar result" to """{"result":"unexpected","error":null}""",
                "missing value" to """{"result":{"name":"d/example","expired":false},"error":null}""",
                "null value" to """{"result":{"name":"d/example","value":null,"expired":false},"error":null}""",
                "object value" to """{"result":{"name":"d/example","value":{},"expired":false},"error":null}""",
                "numeric value" to """{"result":{"name":"d/example","value":42,"expired":false},"error":null}""",
                "array envelope" to """[]""",
            )
        val record = """{"nostr":{"names":{"releases":"$alice"}}}"""
        val success = """{"result":{"name":"d/example","value":${JsonPrimitive(record)},"expired":false},"error":null}"""
        val http = Http.client()
        try {
            malformed.entries.forEachIndexed { index, (label, broken) ->
                val calls = AtomicInteger()
                val (server, url) =
                    rpcServer {
                        if (calls.incrementAndGet() == 1) 200 to broken else 200 to success
                    }
                val lockFile = File(dir, "malformed-$index.lock")
                try {
                    fun recording(asked: MutableList<String>): Nip05Resolver {
                        val delegate = Nip05ResolverFactory.create(http, config(url)) { null }
                        return Nip05Resolver { identifier ->
                            asked += identifier
                            delegate.lookup(identifier)
                        }
                    }

                    val consumerAsked = mutableListOf<String>()
                    val policy =
                        TrustPolicy(
                            listOf(GroupRule.parse("bit.example.quartz=domain")),
                            LockStore(lockFile, false, RecordingLog()),
                            recording(consumerAsked),
                            false,
                            RecordingLog(),
                        )
                    assertFailsWith<MagpieSecurityException>(label) {
                        runBlocking { policy.publisherFor("bit.example.quartz") }
                    }
                    assertEquals(listOf("releases@quartz.example.bit"), consumerAsked, "$label consumer parent lookup")
                    assertEquals(1, calls.get(), "$label consumer Core request count")
                    assertFalse(lockFile.exists(), "$label consumer wrote a lock")

                    calls.set(0)
                    val publisherAsked = mutableListOf<String>()
                    assertFailsWith<ReleaseKeyMismatchException>(label) {
                        runBlocking { ReleaseKeyCheck(recording(publisherAsked)).verify("bit.example.quartz", alice) }
                    }
                    assertEquals(listOf("releases@quartz.example.bit"), publisherAsked, "$label publisher parent lookup")
                    assertEquals(1, calls.get(), "$label publisher Core request count")
                    assertFalse(lockFile.exists(), "$label publisher wrote a lock")
                } finally {
                    server.stop(0)
                }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun malformedCoreProtocolFailuresSkipFallbackButTransportFailuresUseIt() {
        var fallbackCalls = 0
        val fallback =
            object : IElectrumXClient {
                override suspend fun nameShowWithFallback(
                    identifier: String,
                    servers: List<ElectrumxServer>,
                ): NameShowResult {
                    fallbackCalls++
                    return NameShowResult(identifier, "{}")
                }
            }
        val (server, url) =
            rpcServer { request ->
                if ("d/malformed" in request) {
                    200 to """{"result":null,"error":null}"""
                } else {
                    500 to ""
                }
            }
        val http = Http.client()
        try {
            val guardedHttp = http.newBuilder().addInterceptor(CoreRpcEnvelopeGuard).build()
            val core =
                NamecoinCoreRpcClient { guardedHttp }.apply {
                    setConfig(NamecoinCoreRpcConfig(url = url, username = "test", password = "test", timeoutMs = 2000))
                }
            val client = CoreRpcNamecoinClient(core, fallback)
            assertFailsWith<MalformedCoreRpcResponseException> {
                runBlocking { client.nameShowWithFallback("d/malformed", emptyList()) }
            }
            assertEquals(0, fallbackCalls)

            assertEquals("{}", runBlocking { client.nameShowWithFallback("d/transport", emptyList()) }?.value)
            assertEquals(1, fallbackCalls)
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.stop(0)
        }
    }

    @Test
    fun electrumxTransportFailuresAreUnreachableRatherThanAbsent() {
        val port = ServerSocket(0).use { it.localPort }
        val http = Http.client()
        try {
            val resolver =
                Nip05ResolverFactory.create(
                    http,
                    mapOf(
                        EngineConfig.NAMECOIN_BACKEND to EngineConfig.NAMECOIN_BACKEND_ELECTRUMX,
                        EngineConfig.NAMECOIN_ELECTRUMX_SERVERS to "tcp://127.0.0.1:$port",
                        EngineConfig.NAMECOIN_LOOKUP_TIMEOUT_MS to "250",
                    ),
                ) { null }
            val answer = runBlocking { resolver.lookup("releases@example.bit") }
            assertIs<Nip05Answer.Unreachable>(answer)
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun electrumxEndpointsAreExplicitAndRpcUrlsAreValidatedWithoutLeakingCredentials() {
        val servers = parseElectrumxServers(listOf("ssl://one.example:50002", "tcp://two.example:50001"))
        assertEquals(listOf(true, false), servers.map { it.useSsl })
        assertEquals(listOf("one.example", "two.example"), servers.map { it.host })
        assertFailsWith<IllegalArgumentException> { parseElectrumxServers(listOf("https://one.example:50002")) }
        assertFailsWith<IllegalArgumentException> { parseElectrumxServers(listOf("three.example:50002")) }
        assertFailsWith<IllegalArgumentException> { parseElectrumxServers(listOf("ssl://one.example")) }

        val http = Http.client()
        try {
            val marker = "rpc-credential-marker"
            val credentialError =
                assertFailsWith<IllegalArgumentException> {
                    Nip05ResolverFactory.create(http, config("http://$marker@127.0.0.1:8336/")) { null }
                }
            assertFalse(marker in credentialError.message.orEmpty(), credentialError.message)
            val transportError =
                assertFailsWith<IllegalArgumentException> {
                    Nip05ResolverFactory.create(http, config("http://example.com:8336/")) { null }
                }
            assertTrue("use HTTPS" in transportError.message.orEmpty(), transportError.message)
            assertFailsWith<IllegalArgumentException> {
                Nip05ResolverFactory.create(http, config("ftp://127.0.0.1:8336/")) { null }
            }
            listOf("http://localhost:8336/", "http://127.0.0.1:8336/", "http://[::1]:8336/", "https://example.com:8336/")
                .forEach { url -> Nip05ResolverFactory.create(http, config(url)) { null } }
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
