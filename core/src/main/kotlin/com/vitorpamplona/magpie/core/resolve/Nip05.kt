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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/** What a NIP-05 lookup said. Only [Absent] lets a caller move on to the next candidate. */
public sealed interface Nip05Answer {
    public data class Key(
        val pubKey: HexKey,
        /** The relays `nostr.json` lists for the key: where its events can be found. */
        val relays: List<String> = emptyList(),
    ) : Nip05Answer

    /** Definitely no key: the domain doesn't exist, has no `nostr.json` (404), or doesn't list the name. */
    public data object Absent : Nip05Answer

    /** Couldn't tell (timeout, TLS, server error, redirect, garbage): must not count as absent. */
    public data class Unreachable(
        val reason: String,
    ) : Nip05Answer
}

/** Looks NIP-05 identifiers up. */
public fun interface Nip05Resolver {
    public suspend fun lookup(identifier: String): Nip05Answer
}

/**
 * NIP-05 over HTTPS: `https://<domain>/.well-known/nostr.json?name=<name>`, without following
 * redirects (NIP-05 forbids it). [baseUrlTemplate] replaces `https://<domain>` for tests, with
 * `{domain}` standing for the domain.
 */
public class HttpNip05Resolver(
    http: OkHttpClient,
    private val baseUrlTemplate: String? = null,
    /**
     * Whether DNS answers for a host. The JDK reports NXDOMAIN, SERVFAIL and timeouts alike, so
     * a failed lookup only counts as "no such domain" when the registrable domain above it
     * resolves (DNS works, the subdomain just isn't there).
     */
    private val resolves: (String) -> Boolean = { host -> runCatching { InetAddress.getAllByName(host).isNotEmpty() }.getOrDefault(false) },
    private val registrableDomain: (String) -> String? = PublicSuffix::registrableDomain,
) : Nip05Resolver {
    private val client =
        http
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun lookup(identifier: String): Nip05Answer {
        val id = Nip05Id.parse(identifier) ?: return Nip05Answer.Unreachable("not a NIP-05 identifier: $identifier")
        val base = baseUrlTemplate?.replace("{domain}", id.domain)?.trimEnd('/') ?: "https://${id.domain}"
        val url = "$base/.well-known/nostr.json?name=${id.name}"
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    when {
                        response.code == 404 -> {
                            Nip05Answer.Absent
                        }

                        !response.isSuccessful -> {
                            Nip05Answer.Unreachable("HTTP ${response.code} from $url")
                        }

                        else -> {
                            val bytes = response.body.byteStream().use { it.readNBytes(MAX_BODY + 1) }
                            if (bytes.size > MAX_BODY) return@use Nip05Answer.Unreachable("nostr.json at $url is larger than ${MAX_BODY / 1024} KB")
                            val info =
                                try {
                                    Nip05Parser().parse(bytes.decodeToString())
                                } catch (e: Exception) {
                                    return@use Nip05Answer.Unreachable("unreadable nostr.json at $url")
                                }
                            info.names[id.name]
                                ?.lowercase()
                                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                                ?.let { key -> Nip05Answer.Key(key, RelayUrls.clean(info.relays[key].orEmpty())) }
                                ?: Nip05Answer.Absent
                        }
                    }
                }
            } catch (e: UnknownHostException) {
                val parent = registrableDomain(id.domain)
                if (parent != null && parent != id.domain && resolves(parent)) {
                    Nip05Answer.Absent
                } else {
                    Nip05Answer.Unreachable("DNS lookup for ${id.domain} failed")
                }
            } catch (e: IOException) {
                Nip05Answer.Unreachable("${e.javaClass.simpleName}: ${e.message} ($url)")
            }
        }
    }
}

private const val MAX_BODY = 64 * 1024

/** The public suffix list OkHttp ships, for [ReleaseIdentity]. */
public object PublicSuffix {
    /** The registrable domain of [host] (`quartz.vitorpamplona.com` -> `vitorpamplona.com`), or null for a public suffix. */
    public fun registrableDomain(host: String): String? = "https://$host/".toHttpUrlOrNull()?.topPrivateDomain()
}

/** How a domain chain answered for a group. */
public sealed interface VouchedKey {
    public data class Found(
        val identifier: String,
        val pubKey: HexKey,
        /** The relays the domain lists for the key. */
        val relays: List<String> = emptyList(),
    ) : VouchedKey

    /** Every candidate definitely has no key. */
    public data class NoneListed(
        val tried: List<String>,
    ) : VouchedKey

    /** A candidate couldn't be read, so the chain can't be decided. */
    public data class Undecided(
        val identifier: String,
        val reason: String,
    ) : VouchedKey
}

/** Tries [identifiers] in order; the first key wins, an unreachable one stops the walk. */
public suspend fun Nip05Resolver.vouch(identifiers: List<String>): VouchedKey {
    for (identifier in identifiers) {
        when (val answer = lookup(identifier)) {
            is Nip05Answer.Key -> return VouchedKey.Found(identifier, answer.pubKey, answer.relays)
            Nip05Answer.Absent -> continue
            is Nip05Answer.Unreachable -> return VouchedKey.Undecided(identifier, answer.reason)
        }
    }
    return VouchedKey.NoneListed(identifiers)
}
