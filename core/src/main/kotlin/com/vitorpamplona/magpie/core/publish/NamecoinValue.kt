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
package com.vitorpamplona.magpie.core.publish

import com.vitorpamplona.magpie.core.resolve.NamecoinRelayHints
import com.vitorpamplona.magpie.core.resolve.RelayUrls
import com.vitorpamplona.magpie.spec.ReleaseIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Deterministic Amethyst-compatible Namecoin values for Magpie release-key discovery. */
public object NamecoinValue {
    /** Namecoin Core's interoperable UI budget, not its 1,023-byte consensus bound. */
    public const val MAX_VALUE_BYTES: Int = 520

    /** Renders one exact releases key and optional relay hints, or rejects the whole value. */
    public fun render(
        pubKey: String,
        relays: List<String> = emptyList(),
    ): String {
        val key = pubKey.lowercase()
        require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) { "Namecoin release pubkey must be 64 hex characters" }
        relays.forEach { relay ->
            require(RelayUrls.clean(listOf(relay), max = 1).isNotEmpty()) { "Invalid Nostr relay hint: $relay" }
        }
        val normalized = RelayUrls.clean(relays, max = Int.MAX_VALUE)
        val cleaned = NamecoinRelayHints.clean(relays, max = Int.MAX_VALUE)
        require(cleaned == normalized) { "Namecoin relay hints on .bit endpoints are not supported by this MVP" }
        require(cleaned.size <= RelayUrls.MAX_PER_SOURCE) {
            "Namecoin values support at most ${RelayUrls.MAX_PER_SOURCE} relay hints, got ${cleaned.size}"
        }

        val document =
            buildJsonObject {
                put(
                    "nostr",
                    buildJsonObject {
                        put("names", buildJsonObject { put(ReleaseIdentity.NAME, JsonPrimitive(key)) })
                        if (cleaned.isNotEmpty()) {
                            put("relays", buildJsonObject { put(key, JsonArray(cleaned.map(::JsonPrimitive))) })
                        }
                    },
                )
            }
        val rendered = Json.encodeToString(JsonObject.serializer(), document)
        val bytes = rendered.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_VALUE_BYTES) {
            "Namecoin value is $bytes UTF-8 bytes, over the $MAX_VALUE_BYTES-byte design budget. Reduce relay hints; nothing was dropped to make it fit."
        }
        return rendered
    }
}
