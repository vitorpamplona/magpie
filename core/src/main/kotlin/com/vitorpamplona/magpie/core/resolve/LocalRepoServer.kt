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
package com.vitorpamplona.magpie.core.resolve

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.vitorpamplona.magpie.core.util.MagpieLog
import kotlinx.coroutines.runBlocking
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [Router] on the JDK's `com.sun.net.httpserver` at `127.0.0.1:<port>`: no extra server
 * library in the Gradle daemon. Answers GET and HEAD, and nothing to anyone but loopback.
 */
public class LocalRepoServer(
    private val router: Router,
    private val log: MagpieLog,
    /** Tried first, so the repository URL (and Gradle's cache of it) stays the same across builds; 0 for any. */
    preferredPort: Int = 0,
    /**
     * Every URL starts with `/<prefix>/`, derived from the resolver's configuration: a build that
     * reaches a server another daemon started with different trust settings gets 404s, never
     * that daemon's artifacts.
     */
    private val prefix: String = "",
) : AutoCloseable {
    private val executor: ExecutorService =
        // Grows with demand: requests waiting on a relay don't hold up requests the cache can answer.
        Executors.newCachedThreadPool(
            object : ThreadFactory {
                private val count = AtomicInteger()

                override fun newThread(r: Runnable): Thread = Thread(r, "magpie-http-${count.incrementAndGet()}").apply { isDaemon = true }
            },
        )

    private val server: HttpServer = bind(preferredPort)

    private fun bind(preferredPort: Int): HttpServer {
        val created =
            if (preferredPort > 0) {
                try {
                    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort), 64)
                } catch (e: BindException) {
                    null
                }
            } else {
                null
            } ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64)
        return created.apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = this@LocalRepoServer.executor
            start()
        }
    }

    public val port: Int get() = server.address.port

    /** The repository base URL Gradle points at. */
    public val url: String get() = "http://127.0.0.1:$port/" + (if (prefix.isEmpty()) "" else "$prefix/")

    private fun handle(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod
            if (method != "GET" && method != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            val head = method == "HEAD"
            val raw = exchange.requestURI.rawPath
            val path =
                if (prefix.isEmpty()) {
                    raw
                } else if (raw.startsWith("/$prefix/")) {
                    raw.substring(prefix.length + 1)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }
            val response = runBlocking { router.handle(path, head) }
            response.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            when (response) {
                is RouterResponse.Error -> {
                    val body = response.message.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                    if (head) {
                        exchange.sendResponseHeaders(response.status, -1)
                    } else {
                        exchange.sendResponseHeaders(response.status, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }

                is RouterResponse.Head -> {
                    exchange.responseHeaders.add("Content-Type", response.contentType)
                    send(exchange, true, response.length) {}
                }

                is RouterResponse.Bytes -> {
                    exchange.responseHeaders.add("Content-Type", response.contentType)
                    send(exchange, head, response.bytes.size.toLong()) { out -> out.write(response.bytes) }
                }

                is RouterResponse.FileBody -> {
                    exchange.responseHeaders.add("Content-Type", response.contentType)
                    send(exchange, head, response.file.length()) { out -> response.file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                }
            }
        } catch (e: Exception) {
            log.debug("Magpie HTTP exchange failed: ${e.message}")
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            exchange.close()
        }
    }

    private inline fun send(
        exchange: HttpExchange,
        head: Boolean,
        length: Long,
        write: (java.io.OutputStream) -> Unit,
    ) {
        if (head) {
            exchange.responseHeaders.set("Content-Length", length.toString())
            exchange.sendResponseHeaders(200, -1)
        } else {
            exchange.sendResponseHeaders(200, if (length == 0L) -1 else length)
            if (length > 0) exchange.responseBody.use(write)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    public companion object {
        /** A port in 20000..49999 derived from [seed] (the cache directory), so it repeats across builds. */
        public fun preferredPortFor(seed: String): Int = 20_000 + Math.floorMod(seed.hashCode(), 30_000)
    }
}
