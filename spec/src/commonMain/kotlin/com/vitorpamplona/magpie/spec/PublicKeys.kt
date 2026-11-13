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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

/** Parses publisher keys from configuration. Strict: only public forms, never an nsec. */
public object PublicKeys {
    /** The hex pubkey of an `npub`, `nprofile` or 64-char hex string, else null. */
    public fun parseOrNull(value: String): HexKey? {
        val trimmed = value.trim().removePrefix("nostr:")
        if (isSha256Hex(trimmed.lowercase())) return trimmed.lowercase()
        if (!(trimmed.startsWith("npub1") || trimmed.startsWith("nprofile1"))) return null
        return when (val entity = runCatching { Nip19Parser.uriToRoute(trimmed)?.entity }.getOrNull()) {
            is NPub -> entity.hex
            is NProfile -> entity.hex
            else -> null
        }
    }

    public fun parse(value: String): HexKey =
        requireNotNull(parseOrNull(value)) {
            if (value.trim().startsWith(
                    "nsec",
                )
            ) {
                "That is a private key (nsec). Configure the publisher's npub."
            } else {
                "Not a public key: '$value'"
            }
        }

    public fun npub(hex: HexKey): String = NPub.create(hex)
}
