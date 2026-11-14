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
package com.vitorpamplona.magpie.core.nostr

import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.EventVerification
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.DONE_REASON_EOSE
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PagedFetchResult
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllPages
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllWithHooks
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * What relays returned, and which of them actually answered (sent EOSE). An empty [events] list
 * only means "nothing there" when [anyAnswered]; otherwise nobody was asked.
 */
public class QueryResult(
    public val events: List<Event>,
    public val asked: Set<String>,
    public val answered: Set<String>,
) {
    public val anyAnswered: Boolean get() = answered.isNotEmpty()

    /** [events], or a [RelaysUnavailableException] if no relay answered. */
    public fun eventsOrThrow(): List<Event> {
        if (!anyAnswered) throw RelaysUnavailableException(asked)
        return events
    }
}

/** No relay answered a query: absence is unknown, not proven. */
public class RelaysUnavailableException(
    asked: Collection<String>,
    detail: String? = null,
) : IOException(detail ?: "None of the relays answered (${asked.joinToString().ifEmpty { "none configured" }})")

/** What Magpie needs from relays: read events (verified) and publish them. */
public interface NostrIO : AutoCloseable {
    /**
     * Every event matching [filters] on any of [relays]. Each event is checked against the
     * filters and its id and signature verified (pure-Kotlin secp256k1) *before* duplicates are
     * dropped, so one relay sending a forged copy can't hide the genuine one another relay sends.
     * [paged] walks past each relay's result cap (for listings that can be long).
     */
    public suspend fun query(
        relays: Collection<String>,
        filters: List<Filter>,
        paged: Boolean = false,
    ): QueryResult

    /** Publishes [event] to [relays]; the result says which relays accepted it. */
    public suspend fun publish(
        relays: Collection<String>,
        event: Event,
    ): Map<String, Boolean>

    /** The Quartz client, for NIP-46. */
    public val client: INostrClient
}

/**
 * Signature checks are the hot path when listings refresh: remember what already verified.
 * Keyed by id, pubkey and signature, so a different signature string is checked afresh.
 */
public object VerifiedEvents {
    private const val MAX = 50_000
    private val seen = ConcurrentHashMap.newKeySet<String>()

    public fun isValid(event: Event): Boolean {
        val key = event.id + event.pubKey + event.sig
        if (key in seen) return true
        val valid = EventVerification.isValid(event)
        if (valid) {
            if (seen.size >= MAX) seen.clear()
            seen += key
        }
        return valid
    }
}

/** [NostrIO] on Quartz's [NostrClient] over OkHttp websockets. */
public class QuartzNostrIO(
    okHttp: OkHttpClient,
    private val log: MagpieLog,
    private val idleTimeoutMs: Long = 10_000,
    private val publishTimeoutSeconds: Long = 15,
) : NostrIO {
    override val client: NostrClient = NostrClient(BasicOkHttpWebSocket.Builder { okHttp })

    override suspend fun query(
        relays: Collection<String>,
        filters: List<Filter>,
        paged: Boolean,
    ): QueryResult {
        val normalized = normalize(relays)
        val asked = normalized.map { it.url }.toSet()
        if (normalized.isEmpty()) return QueryResult(emptyList(), asked, emptySet())
        val accepted = ConcurrentHashMap<String, Event>()

        fun accept(event: Event): Boolean {
            if (accepted.containsKey(event.id)) return false
            // Junk first (cheap), signatures second (expensive), dedup last.
            if (filters.none { it.match(event) }) return false
            if (!VerifiedEvents.isValid(event)) {
                log.warn("Dropping event ${event.id} from a relay: bad id or signature")
                return false
            }
            return accepted.putIfAbsent(event.id, event) == null
        }

        val answered =
            if (paged) {
                coroutineScope {
                    normalized
                        .map { relay ->
                            async {
                                val result = client.fetchAllPages(relay, filters, idleTimeoutMs) { accept(it) }
                                relay.url.takeIf { result.end == PagedFetchResult.End.DRAINED || result.end == PagedFetchResult.End.LIMIT_REACHED }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .toSet()
                }
            } else {
                val result = client.fetchAllWithHooks(filters = normalized.associateWith { filters }, idleTimeoutMs = idleTimeoutMs) { _, event -> accept(event) }
                result.doneReasons
                    .filterValues { it == DONE_REASON_EOSE }
                    .keys
                    .map { it.url }
                    .toSet()
            }
        return QueryResult(accepted.values.toList(), asked, answered)
    }

    override suspend fun publish(
        relays: Collection<String>,
        event: Event,
    ): Map<String, Boolean> {
        val normalized = normalize(relays)
        if (normalized.isEmpty()) return emptyMap()
        val results = client.publishAndCollectResults(event, normalized.toSet(), publishTimeoutSeconds)
        return normalized.associate { url ->
            val result = results[url]
            if (result != null && !result.accepted) log.info("Relay ${url.url} refused ${event.id}: ${result.message}")
            url.url to (result?.accepted == true)
        }
    }

    private fun normalize(relays: Collection<String>): List<NormalizedRelayUrl> =
        relays.distinct().mapNotNull { url ->
            RelayUrlNormalizer.normalizeOrNull(url).also { if (it == null) log.warn("Ignoring invalid relay URL '$url'") }
        }

    override fun close() {
        // close(), not disconnect(): it also cancels the client's scope and its tickers, which
        // would otherwise outlive the build in the Gradle daemon.
        client.close()
    }
}
