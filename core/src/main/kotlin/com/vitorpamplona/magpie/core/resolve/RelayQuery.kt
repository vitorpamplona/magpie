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

import com.vitorpamplona.magpie.core.nostr.NostrIO
import com.vitorpamplona.magpie.core.nostr.RelaysUnavailableException
import com.vitorpamplona.magpie.core.nostr.VerifiedEvents
import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.ExpiringCache
import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.core.util.StripedMutex
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.MagpieSpec
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The relays each key's domain lists for it in `nostr.json`, as trust lookups find them. Kept on
 * disk too, so a daemon that took the key from the lock (or found the domain unreachable) still
 * knows where to look.
 */
public class RelayHints(
    private val dir: File? = null,
) {
    private val hints = ConcurrentHashMap<HexKey, List<String>>()

    public fun put(
        pubKey: HexKey,
        relays: List<String>,
    ) {
        val clean = RelayUrls.clean(relays)
        if (clean.isEmpty() || hints[pubKey] == clean) return
        hints[pubKey] = clean
        dir?.let { Files2.writeAtomically(File(it, "$pubKey.txt"), clean.joinToString("\n").toByteArray()) }
    }

    public operator fun get(pubKey: HexKey): List<String> =
        hints[pubKey]
            ?: dir
                ?.let { File(it, "$pubKey.txt") }
                ?.takeIf { it.isFile }
                ?.let { file -> RelayUrls.clean(file.readLines()) }
                ?.also { if (it.isNotEmpty()) hints[pubKey] = it }
                .orEmpty()
}

/**
 * The relay queries resolution makes, all filtered to the trusted publisher and sent to that
 * publisher's own relays (the outbox model): the write relays of its NIP-65 list (kind 10002),
 * found on the [indexRelays] and on the relays its domain lists for it ([hints]), plus those
 * domain relays. The index relays are asked for anything else only when a key has neither, so no
 * relay is everyone's single point of failure. Results from every relay are merged; signature
 * checks happen in [NostrIO]. A query no relay answered throws [RelaysUnavailableException] rather
 * than looking like "nothing there". Also keeps the last answer of each query on disk, for
 * offline builds.
 */
public class RelayQuery(
    private val nostr: NostrIO?,
    /** Where keys' relay lists (kind 10002) are looked up. */
    private val indexRelays: List<String>,
    private val cacheDir: File,
    private val log: MagpieLog,
    private val hints: RelayHints = RelayHints(),
    private val clock: Clock = Clock.SYSTEM,
    /** How long a key's relay list is reused before it is read again. */
    private val relayListTtlMs: Long = 10 * 60_000L,
    /** At most this many relay queries at once: bounds what a flood of requests can send to relays. */
    maxConcurrentQueries: Int = 8,
) {
    public val online: Boolean get() = nostr != null

    private val gate = Semaphore(maxConcurrentQueries)
    private val outboxes = ExpiringCache<HexKey, List<String>>(10_000, clock)
    private val outboxLocks = StripedMutex()

    /**
     * Where [publisher]'s events are read from: its write relays and its domain's relays, or the
     * index relays when neither is known.
     */
    public suspend fun relaysFor(publisher: HexKey): List<String> = (outbox(publisher) + hints[publisher]).distinct().ifEmpty { indexRelays }

    /**
     * [publisher]'s NIP-65 write relays, from its newest kind 10002 on the index relays and its
     * domain's relays. Reused for [relayListTtlMs]; the last list seen when no relay answers.
     */
    public suspend fun outbox(publisher: HexKey): List<String> {
        outboxes.get(publisher)?.let { return it }
        return outboxLocks.forKey(publisher).withLock {
            outboxes.get(publisher)?.let { return it }
            val cache = File(cacheDir, "relay-lists/$publisher.json")
            val sources = (indexRelays + hints[publisher]).distinct()
            val io = nostr
            val events =
                if (io == null || sources.isEmpty()) {
                    readEvents(cache)
                } else {
                    val result = gate.withPermit { io.query(sources, listOf(Filter(kinds = listOf(MagpieSpec.KIND_RELAY_LIST), authors = listOf(publisher)))) }
                    if (result.anyAnswered) {
                        result.events.also { if (it.isNotEmpty()) writeEvents(cache, it) }.ifEmpty { readEvents(cache) }
                    } else {
                        log.warn("None of ${sources.joinToString()} answered for the relay list of $publisher; using the last one seen")
                        readEvents(cache)
                    }
                }
            val newest = events.filter { it.kind == MagpieSpec.KIND_RELAY_LIST && it.pubKey == publisher }.maxByOrNull { it.createdAt }
            RelayUrls.writeRelays(newest).also { outboxes.put(publisher, it, relayListTtlMs) }
        }
    }

    /** Releases of [coordinate]'s module at its version. */
    public suspend fun releases(
        coordinate: Coordinate,
        publisher: HexKey,
    ): List<Event> =
        query(
            publisher,
            Filter(
                kinds = listOf(MagpieSpec.KIND_RELEASE),
                authors = listOf(publisher),
                tags = mapOf(MagpieSpec.TAG_MODULE to listOf(coordinate.module.key), MagpieSpec.TAG_VERSION to listOf(coordinate.version)),
            ),
        )

    /** Snapshots of [coordinate]. */
    public suspend fun snapshots(
        coordinate: Coordinate,
        publisher: HexKey,
    ): List<Event> =
        query(
            publisher,
            Filter(
                kinds = listOf(MagpieSpec.KIND_SNAPSHOT),
                authors = listOf(publisher),
                tags = mapOf(MagpieSpec.TAG_MODULE to listOf(coordinate.module.key), MagpieSpec.TAG_VERSION to listOf(coordinate.version)),
            ),
        )

    /** Every release and snapshot of [module]: what `maven-metadata.xml` is built from. */
    public suspend fun moduleEvents(
        module: ModuleId,
        publisher: HexKey,
    ): List<Event> =
        query(
            publisher,
            Filter(
                kinds = listOf(MagpieSpec.KIND_RELEASE, MagpieSpec.KIND_SNAPSHOT),
                authors = listOf(publisher),
                tags = mapOf(MagpieSpec.TAG_MODULE to listOf(module.key)),
            ),
            // A module can have more versions than a relay returns per REQ.
            paged = true,
        )

    /** The publisher's Blossom servers (kind 10063); the last list seen when no relay answers. */
    public suspend fun blossomServers(publisher: HexKey): List<String> {
        val cache = File(cacheDir, "servers/$publisher.json")
        val events =
            if (nostr != null) {
                try {
                    query(publisher, Filter(kinds = listOf(MagpieSpec.KIND_BLOSSOM_SERVERS), authors = listOf(publisher)))
                        .also { if (it.isNotEmpty()) writeEvents(cache, it) }
                        .ifEmpty { readEvents(cache) }
                } catch (e: RelaysUnavailableException) {
                    log.warn("${e.message}; using the last server list seen")
                    readEvents(cache)
                }
            } else {
                readEvents(cache)
            }
        val newest =
            events.filter { it.kind == MagpieSpec.KIND_BLOSSOM_SERVERS && it.pubKey == publisher }.maxByOrNull { it.createdAt }
                ?: return emptyList()
        return newest.tags.filter { it.size >= 2 && it[0] == "server" }.map { it[1].trimEnd('/') }
    }

    /** [publisher]'s matching events; throws [RelaysUnavailableException] when no relay answered. */
    private suspend fun query(
        publisher: HexKey,
        filter: Filter,
        paged: Boolean = false,
    ): List<Event> {
        val io = nostr ?: return emptyList()
        val relays = relaysFor(publisher)
        if (relays.isEmpty()) {
            throw RelaysUnavailableException(
                relays,
                "No relays known for ${PublicKeys.npub(publisher)}: its domain's nostr.json lists none for it, and no indexRelays " +
                    "are configured to find its relay list (kind 10002) on. Set magpie { indexRelays(\"wss://…\") }.",
            )
        }
        return gate.withPermit { io.query(relays, listOf(filter), paged) }.eventsOrThrow().map { MagpieEvents.wrap(it) ?: it }
    }

    /** Persists events as a JSON array of event JSON. */
    public fun writeEvents(
        file: File,
        events: List<Event>,
    ) {
        val json = events.distinctBy { it.id }.joinToString(",", "[", "]") { it.toJson() }
        Files2.writeAtomically(file, json.toByteArray())
    }

    /** Reads events written by [writeEvents], re-checking every signature. */
    public fun readEvents(file: File): List<Event> {
        if (!file.isFile) return emptyList()
        return try {
            val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
            array
                .mapNotNull { element ->
                    val text = if (element is JsonPrimitive) element.jsonPrimitive.content else element.toString()
                    Event.fromJsonOrNull(text)
                }.filter { VerifiedEvents.isValid(it) }
                .map { MagpieEvents.wrap(it) ?: it }
        } catch (e: Exception) {
            log.warn("Ignoring unreadable cache ${file.path}: ${e.message}")
            emptyList()
        }
    }
}
