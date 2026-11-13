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

import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.Hashing
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.isSha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Content-addressed blobs on disk: `<dir>/<sha[0..2]>/<sha>`. A file only lands here after its
 * bytes hashed to its name, so anything found here is trusted forever.
 */
public class BlobStore(
    public val dir: File,
) {
    public fun file(sha256: String): File {
        require(isSha256Hex(sha256)) { "Not a sha256: $sha256" }
        return File(File(dir, sha256.substring(0, 2)), sha256)
    }

    public fun get(sha256: String): File? = file(sha256).takeIf { it.isFile }

    public fun contains(sha256: String): Boolean = get(sha256) != null

    /** Stores [bytes] if they hash to [sha256]. */
    public fun put(
        sha256: String,
        bytes: ByteArray,
    ): File {
        val actual = Hashing.sha256(bytes)
        if (actual != sha256) throw HashMismatchException(sha256, actual, "local bytes")
        val target = file(sha256)
        if (!target.isFile) Files2.writeAtomically(target, bytes)
        return target
    }

    /** Moves a verified temp file in. */
    internal fun adopt(
        sha256: String,
        verified: File,
    ): File {
        val target = file(sha256)
        Files2.ensureDir(target.parentFile)
        if (target.isFile) {
            if (!verified.delete()) verified.deleteOnExit()
        } else {
            Files2.moveAtomically(verified, target)
        }
        return target
    }

    internal fun tempFile(): File {
        Files2.ensureDir(dir)
        return File.createTempFile("blob", ".part", dir)
    }
}

public class HashMismatchException(
    expected: String,
    actual: String,
    source: String,
) : IOException("Blob from $source hashed to $actual, expected $expected")

public class BlobNotFoundException(
    sha256: String,
    tried: List<String>,
    failures: List<String>,
) : IOException(
        "Blob $sha256 is on none of the Blossom servers tried (${tried.joinToString().ifEmpty { "none known" }})" +
            if (failures.isEmpty()) "" else ": " + failures.joinToString("; "),
    )

/**
 * Downloads blobs (BUD-01 `GET /<sha256>`) into a [BlobStore], hashing while streaming. A server
 * that sends the wrong bytes, too many bytes, or fails is skipped for the next one.
 */
public class BlobFetcher(
    private val http: OkHttpClient,
    public val store: BlobStore,
    private val log: MagpieLog,
) {
    /** The blob from the store, or from the first of [servers] that serves the right bytes. */
    public suspend fun fetch(
        sha256: String,
        servers: List<String>,
        expectedSize: Long = -1,
    ): File {
        store.get(sha256)?.let { return it }
        val tried = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (server in servers.map { it.trimEnd('/') }.distinct()) {
            tried += server
            if ("$server/$sha256".toHttpUrlOrNull() == null) {
                failures += "$server: not a URL"
                continue
            }
            try {
                val file = download(server, sha256, expectedSize)
                if (file != null) return file
            } catch (e: IOException) {
                failures += "$server: ${e.message}"
                log.info("Blob $sha256 from $server failed: ${e.message}")
            }
        }
        throw BlobNotFoundException(sha256, tried, failures)
    }

    private suspend fun download(
        server: String,
        sha256: String,
        expectedSize: Long,
    ): File? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$server/$sha256")
                    .get()
                    .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val tmp = store.tempFile()
                try {
                    val md = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    response.body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (expectedSize >= 0 &&
                                    total > expectedSize
                                ) {
                                    throw IOException("more than the expected $expectedSize bytes")
                                }
                                md.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    val actual = Hashing.hex(md.digest())
                    if (actual != sha256) throw HashMismatchException(sha256, actual, server)
                    store.adopt(sha256, tmp)
                } finally {
                    if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit()
                }
            }
        }
}
