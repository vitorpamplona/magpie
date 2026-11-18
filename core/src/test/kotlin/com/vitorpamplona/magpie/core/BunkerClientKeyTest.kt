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

import com.vitorpamplona.magpie.core.publish.BunkerSigner
import com.vitorpamplona.magpie.core.util.MagpieLog
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BunkerClientKeyTest {
    private val log = MagpieLog.NONE
    private val remote = "a".repeat(64)

    @Test
    fun theClientKeyIsKeptAndReused() {
        val dir = Files.createTempDirectory("magpie").toFile()
        val file = BunkerSigner.clientKeyFile(dir, "bunker://$remote?relay=wss://relay.example&secret=s")
        assertEquals(File(File(dir, "bunker-clients"), "$remote.key"), file)
        val first = BunkerSigner.clientKey(file, log)
        assertTrue(file.isFile)
        // A later task, or a later build, is the same client: the bunker already paired with it.
        assertEquals(first.pubKey.toList(), BunkerSigner.clientKey(file, log).pubKey.toList())
        // Only its owner can read it.
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun anUnreadableKeyIsReplaced() {
        val dir = Files.createTempDirectory("magpie").toFile()
        val file = BunkerSigner.clientKeyFile(dir, "bunker://$remote")
        file.parentFile.mkdirs()
        file.writeText("not a key")
        val key = BunkerSigner.clientKey(file, log)
        assertEquals(64, file.readText().trim().length)
        assertEquals(key.pubKey.toList(), BunkerSigner.clientKey(file, log).pubKey.toList())
    }

    @Test
    fun withoutAFileEveryKeyIsNew() {
        assertNotEquals(BunkerSigner.clientKey(null, log).pubKey.toList(), BunkerSigner.clientKey(null, log).pubKey.toList())
    }

    @Test
    fun aBunkerUriThatIsNotAKeyStillGetsItsOwnFile() {
        val dir = Files.createTempDirectory("magpie").toFile()
        val a = BunkerSigner.clientKeyFile(dir, "bunker://alice@example.com")
        val b = BunkerSigner.clientKeyFile(dir, "bunker://bob@example.com")
        assertNotEquals(a, b)
        assertEquals(64 + ".key".length, a.name.length)
    }

    @Test
    fun aKeyAnotherTaskWroteIsKept() {
        val dir = Files.createTempDirectory("magpie").toFile()
        val file = BunkerSigner.clientKeyFile(dir, "bunker://$remote")
        val theirs = BunkerSigner.clientKey(file, log)
        // A second task that found no file when it looked still ends on the key the file holds.
        assertEquals(theirs.pubKey.toList(), BunkerSigner.clientKey(file, log).pubKey.toList())
        assertEquals(listOf(file.name), file.parentFile.list()!!.toList())
    }
}
