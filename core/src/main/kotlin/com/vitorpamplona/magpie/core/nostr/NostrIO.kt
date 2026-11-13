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
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAll
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndCollectResults
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import okhttp3.OkHttpClient

/** What Magpie needs from relays: read events (verified) and publish them. */
public interface NostrIO : AutoCloseable {
    /**
     * Every event matching [filters] on any of [relays], deduplicated, with ids and signatures
     * checked by the pure-Kotlin secp256k1. Events failing the check are dropped and logged.
     */
    public suspend fun query(
        relays: Collection<String>,
        filters: List<Filter>,
    ): List<Event>

    /** Publishes [event] to [relays]; the result says which relays accepted it. */
    public suspend fun publish(
        relays: Collection<String>,
        event: Event,
    ): Map<String, Boolean>

    /** The Quartz client, for NIP-46. */
    public val client: INostrClient
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
    ): List<Event> {
        val normalized = normalize(relays)
        if (normalized.isEmpty()) return emptyList()
        val events = client.fetchAll(filters = normalized.associateWith { filters }, idleTimeoutMs = idleTimeoutMs)
        return events.filter { event ->
            EventVerification.isValid(event).also { valid ->
                if (!valid) log.warn("Dropping event ${event.id} from relays: bad id or signature")
            }
        }
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
        client.disconnect()
    }
}
