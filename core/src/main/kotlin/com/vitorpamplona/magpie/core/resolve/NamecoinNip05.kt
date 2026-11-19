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

import com.vitorpamplona.magpie.spec.ReleaseIdentity
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.IElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NameShowResult
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinLookupException
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolveOutcome
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * Adapts Quartz's detailed Namecoin result to Magpie's trust decision. Only a definite missing
 * name or missing exact entry is absent. Every traversal, parse, transport, and timeout failure
 * stays undecided so a parent-domain key cannot replace a failed child answer.
 */
public class NamecoinNip05Resolver internal constructor(
    private val resolveDetailed: suspend (String) -> NamecoinResolveOutcome,
) : Nip05Resolver {
    public constructor(resolver: NamecoinNameResolver) : this(resolver::resolveDetailed)

    override suspend fun lookup(identifier: String): Nip05Answer {
        val outcome =
            try {
                resolveDetailed(identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Nip05Answer.Unreachable("Namecoin lookup failed with ${e.javaClass.simpleName}")
            }

        return when (outcome) {
            is NamecoinResolveOutcome.Success -> {
                val result = outcome.result
                if (result.localPart != ReleaseIdentity.NAME) {
                    Nip05Answer.Unreachable(
                        "Namecoin matched '${result.localPart}' instead of the exact '${ReleaseIdentity.NAME}' entry for $identifier",
                    )
                } else if (result.pubkey.length != 64 || result.pubkey.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                    Nip05Answer.Unreachable("Namecoin returned an invalid release pubkey for $identifier")
                } else {
                    Nip05Answer.Key(result.pubkey, NamecoinRelayHints.clean(result.relays))
                }
            }

            is NamecoinResolveOutcome.NameNotFound,
            is NamecoinResolveOutcome.NoNostrField,
            -> Nip05Answer.Absent

            else -> Nip05Answer.Unreachable(outcome.reason(identifier))
        }
    }
}

/** Routes only Namecoin identifiers away from HTTPS. Disabled Namecoin never falls back to HTTPS. */
public class DispatchingNip05Resolver(
    private val https: Nip05Resolver,
    private val namecoin: Nip05Resolver?,
) : Nip05Resolver {
    override suspend fun lookup(identifier: String): Nip05Answer =
        if (NamecoinNameResolver.isNamecoinIdentifier(identifier)) {
            namecoin?.lookup(identifier)
                ?: Nip05Answer.Unreachable("Namecoin resolution is disabled for $identifier")
        } else {
            https.lookup(identifier)
        }
}

/** The lock distinguishes a blockchain-backed answer from an HTTPS NIP-05 answer. */
internal fun lockSource(identifier: String): String = if (NamecoinNameResolver.isNamecoinIdentifier(identifier)) "namecoin:$identifier" else "nip05:$identifier"

internal object NamecoinRelayHints {
    fun clean(
        urls: List<String>,
        max: Int = RelayUrls.MAX_PER_SOURCE,
    ): List<String> =
        RelayUrls
            .clean(urls, max = Int.MAX_VALUE)
            .filterNot(::isBitRelay)
            .take(max)

    private fun isBitRelay(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removeSuffix(".") }.getOrNull() ?: return false
        return host == "bit" || host.endsWith(".bit")
    }
}

/** Rejects impossible consensus-sized values before Quartz parses them, including imported values. */
internal class BoundedNamecoinClient(
    private val delegate: IElectrumXClient,
) : IElectrumXClient {
    override suspend fun nameShowWithFallback(
        identifier: String,
        servers: List<com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer>,
    ): NameShowResult? {
        val result = delegate.nameShowWithFallback(identifier, servers)
        val size = result?.value?.toByteArray(Charsets.UTF_8)?.size ?: return result
        if (size > MAX_NAMECOIN_VALUE_BYTES) {
            throw NamecoinLookupException.ServersUnreachable(
                IllegalArgumentException("Namecoin value for $identifier is $size bytes, over the $MAX_NAMECOIN_VALUE_BYTES-byte consensus bound"),
            )
        }
        return result
    }
}

internal const val MAX_NAMECOIN_VALUE_BYTES: Int = 1_023

private fun NamecoinResolveOutcome.reason(identifier: String): String =
    when (this) {
        is NamecoinResolveOutcome.Success -> "unexpected successful Namecoin outcome for $identifier"
        is NamecoinResolveOutcome.NameNotFound -> "Namecoin name not found: $name"
        is NamecoinResolveOutcome.NoNostrField -> "No usable Namecoin Nostr entry in $name"
        is NamecoinResolveOutcome.MalformedRecord -> "Malformed Namecoin record $name: $error"
        is NamecoinResolveOutcome.ImportFailed -> "Namecoin import $target from $name failed: ${cause.reason(target)}"
        is NamecoinResolveOutcome.DelegationFailed -> "Namecoin delegation $target from $name failed: ${cause.reason(target)}"
        is NamecoinResolveOutcome.CyclicReference -> "Namecoin reference cycle at $name"
        is NamecoinResolveOutcome.LookupBudgetExhausted -> "Namecoin lookup budget of $limit exhausted at $name"
        is NamecoinResolveOutcome.DepthLimitExceeded -> "Namecoin traversal depth limit of $limit exceeded at $name"
        is NamecoinResolveOutcome.ServersUnreachable -> "Namecoin backends unreachable for $identifier"
        is NamecoinResolveOutcome.InvalidIdentifier -> "Invalid Namecoin identifier: $identifier"
        NamecoinResolveOutcome.Timeout -> "Namecoin lookup timed out for $identifier"
    }
