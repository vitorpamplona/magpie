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
import com.vitorpamplona.magpie.core.engine.ResolverStack
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.Pin
import com.vitorpamplona.magpie.core.resolve.Router
import com.vitorpamplona.magpie.core.resolve.RouterResponse
import com.vitorpamplona.magpie.core.util.MagpieLog
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Properties

/**
 * Gateway configuration. The trust policy is an allowlist: each group (and its subgroups) is
 * served only from the key listed for it. Unlike the settings plugin, the gateway does no NIP-05
 * trust on first use and keeps no lock: whoever runs it decides the keys.
 */
data class GatewayConfig(
    val relays: List<String>,
    val trust: Map<String, String>,
    val fallbackServers: List<String> = emptyList(),
    val cacheDir: File = File("/var/cache/magpie"),
    val host: String = "0.0.0.0",
    val port: Int = 8080,
) {
    init {
        require(relays.isNotEmpty()) { "Configure at least one relay (MAGPIE_RELAYS)" }
        require(trust.isNotEmpty()) { "Configure at least one group=npub pair (MAGPIE_TRUST)" }
        trust.forEach { (group, key) ->
            val pin = Pin.parse(key)
            require(pin is Pin.Key) { "The gateway trusts keys, not NIP-05 names: $group=$key" }
        }
    }

    fun engineConfig(): Map<String, String> =
        mapOf(
            EngineConfig.RELAYS to relays.joinToString("\n"),
            EngineConfig.GROUPS to trust.entries.joinToString("\n") { "${it.key}=${it.value}" },
            EngineConfig.FALLBACK_SERVERS to fallbackServers.joinToString("\n"),
            EngineConfig.CACHE_DIR to cacheDir.absolutePath,
            EngineConfig.LOCK_FILE to "",
            ResolverStack.MIRROR to "false",
        )

    companion object {
        /**
         * From environment variables, optionally overridden by a properties file (`--config=…`):
         * `MAGPIE_RELAYS` / `relays` (comma-separated), `MAGPIE_TRUST` / `trust`
         * (`com.example=npub1…,org.other=npub1…`), `MAGPIE_FALLBACK_SERVERS` / `fallbackServers`,
         * `MAGPIE_CACHE_DIR` / `cacheDir`, `PORT` / `port`, `HOST` / `host`.
         */
        fun load(
            env: Map<String, String>,
            file: File? = null,
        ): GatewayConfig {
            val props = Properties()
            file?.inputStream()?.use { props.load(it) }

            fun value(
                key: String,
                envKey: String,
            ): String? = props.getProperty(key) ?: env[envKey]

            fun list(
                key: String,
                envKey: String,
            ) = value(key, envKey)
                .orEmpty()
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return GatewayConfig(
                relays = list("relays", "MAGPIE_RELAYS"),
                trust = list("trust", "MAGPIE_TRUST").associate { GroupRule.parse(it).let { rule -> rule.group to it.substringAfter('=').trim() } },
                fallbackServers = list("fallbackServers", "MAGPIE_FALLBACK_SERVERS"),
                cacheDir = File(value("cacheDir", "MAGPIE_CACHE_DIR") ?: "/var/cache/magpie"),
                host = value("host", "HOST") ?: "0.0.0.0",
                port = value("port", "PORT")?.toInt() ?: 8080,
            )
        }
    }
}

/** Logs through SLF4J. */
object Slf4jLog : MagpieLog {
    private val log = LoggerFactory.getLogger("magpie")

    override fun error(message: String) = log.error(message)

    override fun warn(message: String) = log.warn(message)

    override fun lifecycle(message: String) = log.info(message)

    override fun info(message: String) = log.info(message)

    override fun debug(message: String) = log.debug(message)
}

/** Serves [router] at `/` (Maven layout) plus `/healthz`. */
fun Application.magpieGateway(router: Router) {
    routing {
        get("/healthz") { call.respondText("ok") }
        route("{...}") {
            handle { serve(call, router) }
        }
    }
}

private suspend fun serve(
    call: ApplicationCall,
    router: Router,
) {
    val method = call.request.httpMethod
    if (method != HttpMethod.Get && method != HttpMethod.Head) {
        call.respond(HttpStatusCode.MethodNotAllowed)
        return
    }
    val head = method == HttpMethod.Head
    val response = router.handle(call.request.path(), head)
    response.headers.forEach { (k, v) -> call.response.header(k, v) }
    if (head) {
        // Headers only: a HEAD that wrote a body would corrupt keep-alive connections.
        val length =
            when (response) {
                is RouterResponse.Head -> response.length
                is RouterResponse.Bytes -> response.bytes.size.toLong()
                is RouterResponse.FileBody -> response.file.length()
                is RouterResponse.Error -> null
            }
        val type =
            when (response) {
                is RouterResponse.Head -> response.contentType
                is RouterResponse.Bytes -> response.contentType
                is RouterResponse.FileBody -> response.contentType
                is RouterResponse.Error -> "text/plain"
            }
        call.respond(
            object : OutgoingContent.NoContent() {
                override val status: HttpStatusCode = HttpStatusCode.fromValue(response.status)
                override val contentLength: Long? = length
                override val contentType: ContentType = ContentType.parse(type)
            },
        )
        return
    }
    when (response) {
        is RouterResponse.Error -> call.respondText(response.message, status = HttpStatusCode.fromValue(response.status))
        is RouterResponse.Bytes -> call.respondBytes(response.bytes, ContentType.parse(response.contentType))
        is RouterResponse.FileBody -> call.respond(LocalFileContent(response.file, ContentType.parse(response.contentType)))
        is RouterResponse.Head -> call.respond(HttpStatusCode.InternalServerError)
    }
}

/** Starts the gateway; [wait] blocks until it stops. */
fun startGateway(
    config: GatewayConfig,
    wait: Boolean,
): Pair<EmbeddedServer<*, *>, ResolverStack> {
    val stack = ResolverStack.create(config.engineConfig(), Slf4jLog)
    val server = embeddedServer(CIO, port = config.port, host = config.host) { magpieGateway(stack.router) }
    Runtime.getRuntime().addShutdownHook(Thread { stack.close() })
    server.start(wait = wait)
    return server to stack
}

fun main(args: Array<String>) {
    val file = args.firstOrNull { it.startsWith("--config=") }?.substringAfter('=')?.let(::File)
    val config = GatewayConfig.load(System.getenv(), file)
    Slf4jLog.lifecycle("Magpie gateway on ${config.host}:${config.port}, relays ${config.relays}, trusting ${config.trust.keys}")
    startGateway(config, wait = true)
}
