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

import com.vitorpamplona.magpie.core.resolve.Nip05Resolver
import com.vitorpamplona.magpie.core.resolve.PublicSuffix
import com.vitorpamplona.magpie.core.resolve.VouchedKey
import com.vitorpamplona.magpie.core.resolve.vouch
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.ReleaseIdentity
import com.vitorpamplona.quartz.nip01Core.core.HexKey

public class ReleaseKeyMismatchException(
    message: String,
) : IllegalStateException(message)

/**
 * Refuses to publish a group with a key its domain doesn't vouch for: the same check consumers
 * make (`releases@<domain>` down the group's domain chain, or a configured NIP-05 on it), run
 * before anything is signed, so a misconfigured key or a group you don't control fails here and
 * not in someone else's build.
 */
public class ReleaseKeyCheck(
    private val nip05: Nip05Resolver,
    /** A configured identity instead of the `releases@` chain. */
    private val identifier: String? = null,
    private val registrableDomain: (String) -> String? = PublicSuffix::registrableDomain,
) {
    /** Checks [signer] is [group]'s release key; returns the relays the domain lists for it. */
    public suspend fun verify(
        group: String,
        signer: HexKey,
    ): List<String> {
        val vouched = vouched(group, signer)
        if (vouched.pubKey != signer) throw mismatch(vouched, group, signer)
        return vouched.relays
    }

    /**
     * [group]'s release key and the relays its domain lists for it, read from the domain without
     * asking the signer: a publisher checks everything with it and connects only to sign.
     * [signerHint] only fills the `nostr.json` example when no domain lists a key.
     */
    public suspend fun vouched(
        group: String,
        signerHint: HexKey? = null,
    ): VouchedKey.Found {
        val identifiers =
            if (identifier != null) {
                require(ReleaseIdentity.belongsTo(identifier, group, registrableDomain)) {
                    "$identifier can't vouch for $group: its domain must be one of ${ReleaseIdentity.domains(group, registrableDomain).joinToString()}"
                }
                listOf(identifier)
            } else {
                ReleaseIdentity.candidates(group, registrableDomain)
            }
        if (identifiers.isEmpty()) {
            throw ReleaseKeyMismatchException(
                "$group isn't a domain, so no NIP-05 can vouch for its release key. Consumers must pin your npub; " +
                    "turn this check off with magpie { verifyReleaseKey.set(false) }.",
            )
        }
        val listing = "{\"names\":{\"${ReleaseIdentity.NAME}\":\"${signerHint ?: "<your hex pubkey>"}\"}}"
        return when (val answer = nip05.vouch(identifiers)) {
            is VouchedKey.Found -> answer
            is VouchedKey.NoneListed -> {
                throw ReleaseKeyMismatchException(
                    "No domain vouches for a release key for $group (tried ${answer.tried.joinToString()}). Add $listing to " +
                        "https://${answer.tried.last().substringAfter('@')}/.well-known/nostr.json, then publish again.",
                )
            }
            is VouchedKey.Undecided -> {
                throw ReleaseKeyMismatchException("Could not read ${answer.identifier} (${answer.reason}) to check the release key for $group. Try again.")
            }
        }
    }

    public companion object {
        /** [signer] isn't the key [vouched] names for [group]. */
        public fun mismatch(
            vouched: VouchedKey.Found,
            group: String,
            signer: HexKey,
        ): ReleaseKeyMismatchException =
            ReleaseKeyMismatchException(
                "${vouched.identifier} names ${PublicKeys.npub(vouched.pubKey)} as the release key for $group, but you are signing with " +
                    "${PublicKeys.npub(signer)}. Consumers would reject the release. Sign with that key, or update the domain's nostr.json.",
            )
    }
}
