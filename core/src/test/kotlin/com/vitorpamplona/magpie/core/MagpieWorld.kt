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

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.engine.Http
import com.vitorpamplona.magpie.core.engine.ResolverStack
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.PublishResult
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.core.resolve.RouterResponse
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.testkit.InMemoryBlossom
import com.vitorpamplona.magpie.testkit.InMemoryRelay
import com.vitorpamplona.magpie.testkit.TestIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** A relay, two Blossom servers and temp dirs; publish and resolve against them. */
class MagpieWorld : AutoCloseable {
    val relay = InMemoryRelay()
    val blossom = InMemoryBlossom()
    val blossom2 = InMemoryBlossom()
    val root: File = Files.createTempDirectory("magpie-test").toFile()
    val log = RecordingLog()

    fun dir(name: String) = File(root, name).apply { mkdirs() }

    /** Gives [who] a kind 10063 listing [servers] and a NIP-65 write relay list. */
    fun setUpPublisher(
        who: TestIdentity,
        servers: List<String> = listOf(blossom.url),
    ) {
        relay.add(
            who.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_BLOSSOM_SERVERS, servers.map { arrayOf("server", it) }.toTypedArray(), "")),
            who.sign(EventTemplate<Event>(1000, MagpieSpec.KIND_RELAY_LIST, arrayOf(arrayOf("r", relay.url)), "")),
        )
    }

    fun publisher(
        who: TestIdentity,
        signer: SecretKeySigner = SecretKeySigner(who.nsec),
        keyCheck: com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck? = null,
    ): Pair<Publisher, SecretKeySigner> {
        val http = Http.client()
        val nostr = QuartzNostrIO(http, log, idleTimeoutMs = 3_000)
        return Publisher(nostr, BlossomClient(http, log), signer, http, log, keyCheck = keyCheck) to signer
    }

    fun publish(
        who: TestIdentity,
        repo: File,
        version: String,
        configure: PublishSettings.() -> PublishSettings = { this },
    ): PublishResult {
        val (publisher, _) = publisher(who)
        return runBlocking {
            publisher.publish(
                PublishSettings(
                    repoDir = repo,
                    group = "com.example",
                    version = version,
                    rootArtifact = "lib",
                    indexRelays = listOf(relay.url),
                ).configure(),
            )
        }
    }

    fun stack(
        who: TestIdentity,
        cache: File = dir("cache"),
        lock: File? = File(root, "magpie.lock"),
        offline: Boolean = false,
        pin: String = who.npub,
        extra: Map<String, String> = emptyMap(),
    ): ResolverStack =
        ResolverStack.create(
            mapOf(
                EngineConfig.INDEX_RELAYS to relay.url,
                EngineConfig.GROUPS to "com.example=$pin",
                EngineConfig.CACHE_DIR to cache.path,
                EngineConfig.LOCK_FILE to (lock?.path ?: ""),
                EngineConfig.OFFLINE to offline.toString(),
                EngineConfig.RELAY_TIMEOUT_MS to "3000",
            ) + extra,
            log,
        )

    override fun close() {
        relay.close()
        blossom.close()
        blossom2.close()
        root.deleteRecursively()
    }
}

class RecordingLog : MagpieLog {
    val lines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun error(message: String) {
        lines += "ERROR $message"
    }

    override fun warn(message: String) {
        lines += "WARN $message"
    }

    override fun lifecycle(message: String) {
        lines += message
    }

    override fun info(message: String) {
        lines += "INFO $message"
    }
}

fun ResolverStack.get(path: String): RouterResponse = runBlocking { router.handle(path) }

fun RouterResponse.bytes(): ByteArray =
    when (this) {
        is RouterResponse.Bytes -> bytes
        is RouterResponse.FileBody -> file.readBytes()
        is RouterResponse.Error -> throw AssertionError("HTTP $status: $message")
        is RouterResponse.Head -> throw AssertionError("HEAD answer has no body")
    }

fun RouterResponse.text(): String = bytes().decodeToString()
