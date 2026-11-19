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
package com.vitorpamplona.magpie.core.publish

import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64

/** A blob to host: its hash, size, and where its bytes come from. */
public class BlobSource(
    public val sha256: String,
    public val size: Long,
    public val contentType: String,
    private val file: File? = null,
    private val bytes: ByteArray? = null,
) {
    init {
        require(file != null || bytes != null)
    }

    internal fun body(): RequestBody = file?.asRequestBody(contentType.toMediaType()) ?: bytes!!.toRequestBody(contentType.toMediaType())
}

public sealed interface Preflight {
    public data object Accepted : Preflight

    /** The server doesn't implement BUD-06, or wants an authorization to answer: only the upload will tell. */
    public data object Unknown : Preflight

    public data class Refused(
        val status: Int,
        val reason: String,
    ) : Preflight
}

public data class UploadOutcome(
    val ok: Boolean,
    val status: Int,
    val reason: String,
) {
    /** The server rejected the authorization itself. */
    val isAuthRefusal: Boolean get() = status == 401 || status == 403
}

/** BUD-01/02/06 calls against one Blossom server at a time. */
public class BlossomClient(
    private val http: OkHttpClient,
    private val log: MagpieLog,
) {
    /** `HEAD /<sha256>`: does the server already have it? */
    public suspend fun has(
        server: String,
        sha256: String,
    ): Boolean = size(server, sha256) != null

    /**
     * `HEAD /<sha256>`: the blob's length if the server has it (-1 when it doesn't say), else
     * null. Unreachable servers and malformed URLs count as not having it.
     */
    public suspend fun size(
        server: String,
        sha256: String,
    ): Long? =
        withContext(Dispatchers.IO) {
            val url = "${server.trimEnd('/')}/$sha256".toHttpUrlOrNull() ?: return@withContext null
            try {
                retryingBusy(
                    Request
                        .Builder()
                        .url(url)
                        .head()
                        .build(),
                ) { response ->
                    if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() ?: -1L else null
                }
            } catch (e: IOException) {
                log.info("HEAD $server/$sha256 failed: ${e.message}")
                null
            }
        }

    /** BUD-06 `HEAD /upload`: would the server take this blob? */
    public suspend fun preflight(
        server: String,
        blob: BlobSource,
    ): Preflight =
        withContext(Dispatchers.IO) {
            val url = "${server.trimEnd('/')}/upload".toHttpUrlOrNull() ?: return@withContext Preflight.Refused(0, "not a URL: $server")
            val request =
                Request
                    .Builder()
                    .url(url)
                    .head()
                    .header("X-SHA-256", blob.sha256)
                    .header("X-Content-Length", blob.size.toString())
                    .header("X-Content-Type", blob.contentType)
                    .build()
            try {
                retryingBusy(request) { response ->
                    when {
                        response.isSuccessful -> Preflight.Accepted
                        // 401: the server wants an authorization even to answer, and nothing is
                        // signed until every check passes, so only the signed upload can tell.
                        response.code == 401 || response.code == 404 || response.code == 405 || response.code == 501 -> Preflight.Unknown
                        // Still busy after the retries: a rate limit, not a refusal of the blob.
                        response.code in BUSY -> Preflight.Unknown
                        else -> Preflight.Refused(response.code, response.header("X-Reason") ?: response.message)
                    }
                }
            } catch (e: IOException) {
                Preflight.Refused(0, e.message ?: "unreachable")
            }
        }

    /**
     * BUD-02 `PUT /upload` with a `Nostr <base64>` authorization. Success means a 2xx *and* a
     * blob descriptor naming the same sha256: a server that stored something else didn't store it.
     */
    public suspend fun upload(
        server: String,
        blob: BlobSource,
        auth: Event,
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            val url = "${server.trimEnd('/')}/upload".toHttpUrlOrNull() ?: return@withContext UploadOutcome(false, 0, "not a URL: $server")
            val header = "Nostr " + Base64.getEncoder().encodeToString(auth.toJson().toByteArray())
            val request =
                Request
                    .Builder()
                    .url(url)
                    .put(blob.body())
                    .header("Authorization", header)
                    .header("X-SHA-256", blob.sha256)
                    .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use UploadOutcome(false, response.code, response.header("X-Reason") ?: response.message)
                    val stored =
                        runCatching {
                            ((Json.parseToJsonElement(response.body.string()) as JsonObject)["sha256"] as JsonPrimitive).content.lowercase()
                        }.getOrNull()
                    if (stored == blob.sha256) {
                        UploadOutcome(true, response.code, "stored")
                    } else {
                        UploadOutcome(false, response.code, "the server's blob descriptor says ${stored ?: "nothing"}, not ${blob.sha256}")
                    }
                }
            } catch (e: IOException) {
                UploadOutcome(false, 0, e.message ?: "unreachable")
            }
        }

    /**
     * Runs [request], retrying a server that answers busy (429, 502-504) up to [BUSY_RETRIES]
     * times, after its `Retry-After` (at most 10 s) or a short backoff, then hands [read] the
     * last answer.
     */
    private suspend fun <T> retryingBusy(
        request: Request,
        read: (Response) -> T,
    ): T {
        var attempt = 0
        while (true) {
            var waitMs = -1L
            val result =
                http.newCall(request).execute().use { response ->
                    if (response.code in BUSY && attempt < BUSY_RETRIES) {
                        waitMs = response
                            .header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.let { it.coerceIn(0, 10) * 1000 } ?: (500L shl attempt)
                        null
                    } else {
                        read(response)
                    }
                }
            if (waitMs < 0) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            attempt++
            delay(waitMs)
        }
    }

    public companion object {
        /** Answers that mean the server is busy, not that it refuses: worth asking again. */
        private val BUSY = setOf(429, 502, 503, 504)
        private const val BUSY_RETRIES = 3

        /**
         * One BUD-02 upload authorization covering every hash in [hashes] on every server in
         * [servers]. A release signs one per batch of [hashes] before signing the release.
         */
        public fun uploadAuth(
            hashes: Collection<String>,
            servers: Collection<String>,
            createdAt: Long,
            expiration: Long = createdAt + 3600,
            description: String = "Upload Maven artifacts",
        ): EventTemplate<Event> {
            val tags = mutableListOf<Array<String>>()
            tags += arrayOf("t", "upload")
            tags += arrayOf("expiration", expiration.toString())
            hashes.distinct().forEach { tags += arrayOf("x", it) }
            servers
                .map { BlossomServerUrl.domain(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { tags += arrayOf("server", it) }
            return EventTemplate(createdAt, MagpieSpec.KIND_BLOSSOM_AUTH, tags.toTypedArray(), description)
        }
    }
}
