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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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

    /** The server doesn't implement BUD-06; only the upload will tell. */
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
)

/** BUD-01/02/06 calls against one Blossom server at a time. */
public class BlossomClient(
    private val http: OkHttpClient,
    private val log: MagpieLog,
) {
    /** `HEAD /<sha256>`: does the server already have it? */
    public suspend fun has(
        server: String,
        sha256: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("${server.trimEnd('/')}/$sha256")
                            .head()
                            .build(),
                    ).execute()
                    .use { it.isSuccessful }
            } catch (e: IOException) {
                log.info("HEAD $server/$sha256 failed: ${e.message}")
                false
            }
        }

    /** BUD-06 `HEAD /upload`: would the server take this blob? */
    public suspend fun preflight(
        server: String,
        blob: BlobSource,
    ): Preflight =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("${server.trimEnd('/')}/upload")
                    .head()
                    .header("X-SHA-256", blob.sha256)
                    .header("X-Content-Length", blob.size.toString())
                    .header("X-Content-Type", blob.contentType)
                    .build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Preflight.Accepted
                        response.code == 404 || response.code == 405 || response.code == 501 -> Preflight.Unknown
                        else -> Preflight.Refused(response.code, response.header("X-Reason") ?: response.message)
                    }
                }
            } catch (e: IOException) {
                Preflight.Refused(0, e.message ?: "unreachable")
            }
        }

    /** BUD-02 `PUT /upload` with a `Nostr <base64>` authorization. */
    public suspend fun upload(
        server: String,
        blob: BlobSource,
        auth: Event,
    ): UploadOutcome =
        withContext(Dispatchers.IO) {
            val header = "Nostr " + Base64.getEncoder().encodeToString(auth.toJson().toByteArray())
            val request =
                Request
                    .Builder()
                    .url("${server.trimEnd('/')}/upload")
                    .put(blob.body())
                    .header("Authorization", header)
                    .header("X-SHA-256", blob.sha256)
                    .build()
            try {
                http.newCall(request).execute().use { response ->
                    UploadOutcome(response.isSuccessful, response.code, response.header("X-Reason") ?: response.message)
                }
            } catch (e: IOException) {
                UploadOutcome(false, 0, e.message ?: "unreachable")
            }
        }

    public companion object {
        /**
         * One BUD-02 upload authorization covering every hash in [hashes] on every server in
         * [servers]: the first of a release's two signatures.
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
