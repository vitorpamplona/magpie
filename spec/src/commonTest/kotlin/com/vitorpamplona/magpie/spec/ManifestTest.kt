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
package com.vitorpamplona.magpie.spec

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val coordinate = Coordinate("com.example", "lib", "1.0")

    @Test
    fun canonicalJsonIsSortedAndCompact() {
        val manifest =
            Manifest(
                coordinate,
                linkedMapOf(
                    "lib/1.0/lib-1.0.pom" to ManifestFile(b, 20),
                    "lib-jvm/1.0/lib-jvm-1.0.jar" to ManifestFile(c, 300),
                    "lib/1.0/lib-1.0.module" to ManifestFile(a, 10),
                ),
            )
        assertEquals(
            """{"coordinate":"com.example:lib:1.0","files":{"lib-jvm/1.0/lib-jvm-1.0.jar":{"sha256":"$c","size":300},""" +
                """"lib/1.0/lib-1.0.module":{"sha256":"$a","size":10},"lib/1.0/lib-1.0.pom":{"sha256":"$b","size":20}},"schema":1}""",
            manifest.toCanonicalJson(),
        )
    }

    @Test
    fun hashIsIndependentOfInsertionOrder() {
        val one =
            Manifest(coordinate, linkedMapOf("lib/1.0/lib-1.0.pom" to ManifestFile(a, 1), "lib/1.0/lib-1.0.jar" to ManifestFile(b, 2)))
        val two =
            Manifest(coordinate, linkedMapOf("lib/1.0/lib-1.0.jar" to ManifestFile(b, 2), "lib/1.0/lib-1.0.pom" to ManifestFile(a, 1)))
        assertEquals(one.sha256Hex(), two.sha256Hex())
        assertEquals(sha256(one.toBytes()).toHexKey(), one.sha256Hex())
    }

    @Test
    fun roundTrip() {
        val manifest =
            Manifest(
                coordinate,
                mapOf(
                    "lib/1.0/lib-1.0.pom" to ManifestFile(a, 1),
                    "lib-jvm/1.0/lib-jvm-1.0-sources.jar" to ManifestFile(b, 2),
                ),
            )
        val parsed = Manifest.parse(manifest.toCanonicalJson())
        assertEquals(manifest, parsed)
        assertEquals(manifest.sha256Hex(), parsed.sha256Hex())
        assertEquals(listOf("lib", "lib-jvm"), parsed.artifacts)
    }

    @Test
    fun parseIgnoresUnknownFieldsButRefusesNewerSchema() {
        val parsed = Manifest.parse("""{"schema":1,"extra":true,"coordinate":"com.example:lib:1.0","files":{}}""")
        assertEquals(0, parsed.files.size)
        assertFailsWith<IllegalArgumentException> { Manifest.parse("""{"schema":2,"coordinate":"com.example:lib:1.0","files":{}}""") }
    }

    @Test
    fun rejectsBadKeys() {
        assertFailsWith<IllegalArgumentException> { Manifest(coordinate, mapOf("../1.0/lib-1.0.jar" to ManifestFile(a, 1))) }
        assertFailsWith<IllegalArgumentException> { Manifest(coordinate, mapOf("lib/2.0/lib-2.0.jar" to ManifestFile(a, 1))) }
        assertFailsWith<IllegalArgumentException> { Manifest(coordinate, mapOf("lib/1.0/other-1.0.jar" to ManifestFile(a, 1))) }
        assertFailsWith<IllegalArgumentException> { Manifest(coordinate, mapOf("lib-1.0.jar" to ManifestFile(a, 1))) }
        assertFailsWith<IllegalArgumentException> { ManifestFile("ABC", 1) }
    }

    @Test
    fun mergeUnionsPartials() {
        val linux =
            Manifest(
                coordinate,
                mapOf(
                    "lib/1.0/lib-1.0.module" to ManifestFile(a, 1),
                    "lib-linuxx64/1.0/lib-linuxx64-1.0.klib" to ManifestFile(b, 2),
                ),
            )
        val mac =
            Manifest(
                coordinate,
                mapOf(
                    "lib/1.0/lib-1.0.module" to ManifestFile(a, 1),
                    "lib-iosarm64/1.0/lib-iosarm64-1.0.klib" to ManifestFile(c, 3),
                ),
            )
        val merged = Manifest.merge(listOf(linux, mac))
        assertEquals(3, merged.files.size)
        assertEquals(listOf("lib", "lib-iosarm64", "lib-linuxx64"), merged.artifacts)
        assertEquals(merged.sha256Hex(), Manifest.merge(listOf(mac, linux)).sha256Hex())
    }

    @Test
    fun mergeRefusesConflicts() {
        val one = Manifest(coordinate, mapOf("lib/1.0/lib-1.0.module" to ManifestFile(a, 1)))
        val two = Manifest(coordinate, mapOf("lib/1.0/lib-1.0.module" to ManifestFile(b, 1)))
        assertFailsWith<IllegalArgumentException> { Manifest.merge(listOf(one, two)) }
        val other = Manifest(Coordinate("com.example", "lib", "1.1"), emptyMap())
        assertFailsWith<IllegalArgumentException> { Manifest.merge(listOf(one, other)) }
    }

    @Test
    fun primaryFiles() {
        listOf("lib-1.0.jar", "lib-1.0.aar", "lib-1.0.klib", "lib-1.0.pom", "lib-1.0.module", "lib-1.0-cinterop-x.klib").forEach {
            assertEquals(true, isPrimaryFile(it), it)
        }
        listOf("lib-1.0-sources.jar", "lib-1.0-javadoc.jar", "lib-1.0.jar.asc", "lib-1.0-kotlin-tooling-metadata.json").forEach {
            assertEquals(false, isPrimaryFile(it), it)
        }
    }
}
