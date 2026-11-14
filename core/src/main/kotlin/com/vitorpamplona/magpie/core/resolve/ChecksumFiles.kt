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
import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `.md5`, `.sha1`, `.sha256` and `.sha512` sidecars for every file, `.asc` included, in Maven
 * Central's format: lowercase hex, nothing else. Blobs never change, so a checksum computed once
 * is kept next to the blob store (`<dir>/<sha256>.<algorithm>`) and in memory.
 */
public class ChecksumFiles(
    private val dir: File?,
) {
    private val memory = ConcurrentHashMap<String, String>()

    /** The [algorithm] checksum of the blob [blobSha256] stored in [file]. */
    public fun ofBlob(
        blobSha256: String,
        file: File,
        algorithm: ChecksumAlgorithm,
    ): String {
        if (algorithm == ChecksumAlgorithm.SHA256) return blobSha256
        val key = "$blobSha256.${algorithm.extension}"
        memory[key]?.let { return it }
        val sidecar = dir?.let { File(File(it, blobSha256.substring(0, 2)), key) }
        sidecar?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == algorithm.hexLength }?.let {
            memory[key] = it
            return it
        }
        val value = Hashing.checksum(file, algorithm)
        memory[key] = value
        sidecar?.let { Files2.writeAtomically(it, value.toByteArray()) }
        return value
    }

    public fun ofBytes(
        bytes: ByteArray,
        algorithm: ChecksumAlgorithm,
    ): String = Hashing.checksum(bytes, algorithm)
}
