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
package com.vitorpamplona.magpie.testkit

import com.vitorpamplona.magpie.spec.EventVerification
import com.vitorpamplona.quartz.nip01Core.core.Event
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Blossom server on `http://127.0.0.1:<port>`, blobs in memory:
 * - BUD-01 `GET`/`HEAD /<sha256>[.ext]`
 * - BUD-02 `PUT /upload` with a kind 24242 `Authorization: Nostr <base64>` (verified)
 * - BUD-06 `HEAD /upload` preflight with `X-SHA-256`, `X-Content-Length`, `X-Content-Type`
 *
 * The knobs make it misbehave the ways real servers do.
 */
class InMemoryBlossom(
    /** Largest blob accepted, bytes. */
    @Volatile var maxUploadSize: Long = Long.MAX_VALUE,
    /** Reject auth events with more than one `x` tag, as some servers do. */
    @Volatile var rejectMultiHashAuth: Boolean = false,
    /** Require a valid auth event on uploads. */
    @Volatile var requireAuth: Boolean = true,
    /** Answer an unauthorized BUD-06 preflight with 401, as blossom.band does. */
    @Volatile var preflightNeedsAuth: Boolean = false,
) : AutoCloseable {
    val blobs = ConcurrentHashMap<String, ByteArray>()
    val uploads = AtomicInteger()
    val downloads = AtomicInteger()
    val heads = AtomicInteger()
    val preflights = AtomicInteger()

    /** Auth events seen on uploads, by id. */
    val authEvents = ConcurrentHashMap<String, Event>()

    /** Refuse every upload's authorization (401), whatever it covers. */
    @Volatile
    var refuseAllUploads: Boolean = false

    /** Store uploads but answer with a descriptor naming another hash. */
    @Volatile
    var misreportHash: Boolean = false

    /** When false, every request fails with 503. */
    @Volatile
    var available: Boolean = true

    private val engine: EmbeddedServer<*, *> =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                route("{...}") {
                    handle { serve(call) }
                }
            }
        }.start(wait = false)

    val port: Int =
        runBlocking {
            engine.engine
                .resolvedConnectors()
                .first()
                .port
        }

    val url: String = "http://127.0.0.1:$port"

    private suspend fun serve(call: ApplicationCall) {
        if (!available) {
            call.respondText("unavailable", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val path = call.request.path()
        val method = call.request.httpMethod
        when {
            path == "/upload" && method == HttpMethod.Head -> preflight(call)
            path == "/upload" && method == HttpMethod.Put -> upload(call)
            method == HttpMethod.Get || method == HttpMethod.Head ->
                get(
                    call,
                    path.removePrefix("/").substringBefore('.'),
                    method == HttpMethod.Head,
                )
            else -> call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }

    private suspend fun get(
        call: ApplicationCall,
        sha: String,
        head: Boolean,
    ) {
        val blob = blobs[sha]
        if (blob == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        if (head) {
            heads.incrementAndGet()
            // Headers only, with the blob's real length, as real servers answer HEAD.
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val contentLength: Long = blob.size.toLong()
                    override val contentType: ContentType = ContentType.Application.OctetStream
                },
            )
        } else {
            downloads.incrementAndGet()
            call.respondBytes(blob, ContentType.Application.OctetStream)
        }
    }

    private suspend fun preflight(call: ApplicationCall) {
        preflights.incrementAndGet()
        val size = call.request.header("X-Content-Length")?.toLongOrNull()
        val sha = call.request.header("X-SHA-256")
        if (size == null || sha == null || sha.length != 64) {
            call.response.header("X-Reason", "missing X-SHA-256 or X-Content-Length")
            call.respond(HttpStatusCode.BadRequest)
            return
        }
        if (size > maxUploadSize) {
            call.response.header("X-Reason", "File too large. Max allowed size is $maxUploadSize bytes.")
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        if (preflightNeedsAuth && call.request.header("Authorization") == null) {
            call.response.header("X-Reason", "Missing Blossom Authorization header, authorization required")
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun upload(call: ApplicationCall) {
        val body = call.receive<ByteArray>()
        val sha = sha256Hex(body)
        if (body.size > maxUploadSize) {
            call.response.header("X-Reason", "File too large")
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        if (refuseAllUploads) {
            call.response.header("X-Reason", "uploads are closed")
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        if (requireAuth) {
            val problem = authProblem(call.request.header(HttpHeaders.Authorization), sha)
            if (problem != null) {
                call.response.header("X-Reason", problem)
                call.respond(HttpStatusCode.Unauthorized)
                return
            }
        }
        blobs[sha] = body
        uploads.incrementAndGet()
        call.respondText(
            """{"url":"$url/$sha","sha256":"${if (misreportHash) "0".repeat(64) else sha}","size":${body.size},"type":"application/octet-stream","uploaded":${System.currentTimeMillis() / 1000}}""",
            ContentType.Application.Json,
        )
    }

    private fun authProblem(
        header: String?,
        sha: String,
    ): String? {
        if (header == null || !header.startsWith("Nostr ")) return "missing auth"
        val event =
            runCatching { Event.fromJson(String(Base64.getDecoder().decode(header.removePrefix("Nostr ").trim()))) }.getOrNull()
                ?: return "unparseable auth"
        if (event.kind != 24242) return "wrong auth kind"
        if (!EventVerification.isValid(event)) return "bad auth signature"
        val verb = event.tags.firstOrNull { it.size >= 2 && it[0] == "t" }?.get(1)
        if (verb != "upload") return "auth verb is $verb"
        val expiration =
            event.tags
                .firstOrNull { it.size >= 2 && it[0] == "expiration" }
                ?.get(1)
                ?.toLongOrNull() ?: return "auth has no expiration"
        if (expiration < System.currentTimeMillis() / 1000) return "auth expired"
        val hashes = event.tags.filter { it.size >= 2 && it[0] == "x" }.map { it[1] }
        if (rejectMultiHashAuth && hashes.size > 1) return "one x tag per auth, please"
        if (hashes.isNotEmpty() && sha !in hashes) return "auth does not cover $sha"
        authEvents[event.id] = event
        return null
    }

    /** Stores a blob directly. */
    fun put(bytes: ByteArray): String = sha256Hex(bytes).also { blobs[it] = bytes }

    override fun close() {
        engine.stop(0, 500)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
