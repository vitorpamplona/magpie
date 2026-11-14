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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer

/** Relay lists read from other people's documents and events, made safe to dial. */
public object RelayUrls {
    /** At most this many relays from one source: a key's outbox is a few relays, not a crawl list. */
    public const val MAX_PER_SOURCE: Int = 8

    /** Normalized `wss://`/`ws://` URLs, duplicates and anything else dropped, at most [max]. */
    public fun clean(
        urls: List<String>,
        max: Int = MAX_PER_SOURCE,
    ): List<String> =
        urls
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("wss://", ignoreCase = true) || it.startsWith("ws://", ignoreCase = true) }
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }
            .distinct()
            .take(max)
            .toList()

    /** The write relays of a NIP-65 relay list (kind 10002): `r` tags with no marker or `write`. */
    public fun writeRelays(relayList: Event?): List<String> =
        clean(
            relayList
                ?.tags
                ?.filter { it.size >= 2 && it[0] == "r" && (it.size == 2 || it[2] == "write") }
                ?.map { it[1] }
                .orEmpty(),
        )
}
