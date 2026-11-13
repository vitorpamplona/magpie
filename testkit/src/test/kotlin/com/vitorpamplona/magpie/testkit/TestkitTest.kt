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

import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.MagpieSnapshotEvent
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.ManifestFile
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.spec.WinnerSelection
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals

class TestkitTest {
    private val alice = TestIdentity.ALICE

    @Test
    fun relayIndexesMagpieTagsAndReplacesSnapshots() {
        InMemoryRelay().use { relay ->
            val m1 =
                Manifest(
                    Coordinate("com.example", "lib", "1.0"),
                    mapOf("lib-jvm/1.0/lib-jvm-1.0.jar" to ManifestFile("a".repeat(64), 1)),
                )
            val m2 = Manifest(Coordinate("com.example", "lib", "2.0"), emptyMap())
            relay.add(alice.sign(MagpieReleaseEvent.build(m1, createdAt = 100)), alice.sign(MagpieReleaseEvent.build(m2, createdAt = 200)))
            val snap = Manifest(Coordinate("com.example", "lib", "3.0-SNAPSHOT"), emptyMap())
            relay.add(
                alice.sign(MagpieSnapshotEvent.build(snap, createdAt = 100)),
                alice.sign(MagpieSnapshotEvent.build(snap, createdAt = 300)),
            )

            val client = NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() })
            try {
                val byModule =
                    runBlocking {
                        client.fetchAll(
                            relay.url,
                            Filter(
                                kinds = listOf(MagpieSpec.KIND_RELEASE),
                                tags =
                                    mapOf("j" to listOf("com.example:lib-jvm")),
                            ),
                            5_000,
                        )
                    }
                assertEquals(listOf("1.0"), byModule.map { (it.tags.first { t -> t[0] == "v" })[1] })

                val byVersion =
                    runBlocking {
                        client.fetchAll(
                            relay.url,
                            Filter(
                                kinds = listOf(MagpieSpec.KIND_RELEASE),
                                authors = listOf(alice.pubKey),
                                tags =
                                    mapOf("j" to listOf("com.example:lib"), "v" to listOf("2.0")),
                            ),
                            5_000,
                        )
                    }
                assertEquals(1, byVersion.size)

                val snapshots = runBlocking { client.fetchAll(relay.url, Filter(kinds = listOf(MagpieSpec.KIND_SNAPSHOT)), 5_000) }
                // Relays may still hand back a replaced version; the newest one is the snapshot.
                val newest = WinnerSelection.snapshot(snapshots, ModuleId("com.example", "lib"), "3.0-SNAPSHOT", alice.pubKey)
                assertEquals(300L, newest?.createdAt)
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun blossomStoresVerifiedUploadsAndPreflights() {
        InMemoryBlossom(maxUploadSize = 10).use { blossom ->
            val http = OkHttpClient()
            val bytes = "hello".toByteArray()
            val sha = InMemoryBlossom.sha256Hex(bytes)

            val unauthorized =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("${blossom.url}/upload")
                            .put(bytes.toRequestBody())
                            .build(),
                    ).execute()
            assertEquals(401, unauthorized.code)
            unauthorized.close()

            val auth =
                alice.sign(
                    com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent.build(
                        "upload",
                        "Upload",
                        hash = sha,
                        size = bytes.size.toLong(),
                    ),
                )
            val header =
                "Nostr " +
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(auth.toJson().toByteArray())
            val ok =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("${blossom.url}/upload")
                            .header("Authorization", header)
                            .put(bytes.toRequestBody())
                            .build(),
                    ).execute()
            assertEquals(200, ok.code)
            ok.close()

            val get = http.newCall(Request.Builder().url("${blossom.url}/$sha").build()).execute()
            assertEquals("hello", get.body.string())

            val tooBig =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url("${blossom.url}/upload")
                            .head()
                            .header("X-SHA-256", sha)
                            .header("X-Content-Length", "11")
                            .build(),
                    ).execute()
            assertEquals(413, tooBig.code)
            tooBig.close()
        }
    }
}
