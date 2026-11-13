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
package com.vitorpamplona.magpie.core.util

import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Where the engine reports. The Gradle side maps it to Gradle's logger. */
public interface MagpieLog {
    public fun error(message: String)

    public fun warn(message: String)

    public fun lifecycle(message: String)

    public fun info(message: String)

    public fun debug(message: String) {}

    public companion object {
        public val NONE: MagpieLog =
            object : MagpieLog {
                override fun error(message: String) {}

                override fun warn(message: String) {}

                override fun lifecycle(message: String) {}

                override fun info(message: String) {}
            }

        public val STDERR: MagpieLog =
            object : MagpieLog {
                override fun error(message: String) = System.err.println("[magpie] ERROR $message")

                override fun warn(message: String) = System.err.println("[magpie] WARN $message")

                override fun lifecycle(message: String) = System.err.println("[magpie] $message")

                override fun info(message: String) = System.err.println("[magpie] $message")
            }
    }
}

public object Hashing {
    public fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    public fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    public fun sha256(file: File): String = file.inputStream().use { digest(it, "SHA-256") }

    public fun digest(
        input: InputStream,
        algorithm: String,
    ): String {
        val md = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            md.update(buffer, 0, read)
        }
        return hex(md.digest())
    }

    public fun checksum(
        file: File,
        algorithm: ChecksumAlgorithm,
    ): String = file.inputStream().use { digest(it, algorithm.jdkName) }

    public fun checksum(
        bytes: ByteArray,
        algorithm: ChecksumAlgorithm,
    ): String = hex(MessageDigest.getInstance(algorithm.jdkName).digest(bytes))

    private val HEX = "0123456789abcdef".toCharArray()
}

public object Files2 {
    /** Writes [bytes] to [target] through a temp file and an atomic move, so readers never see half a file. */
    public fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        ensureDir(target.parentFile)
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            moveAtomically(tmp, target)
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit()
        }
    }

    public fun moveAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    public fun ensureDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("Could not create $dir")
    }
}

/** A clock the tests can move. */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
