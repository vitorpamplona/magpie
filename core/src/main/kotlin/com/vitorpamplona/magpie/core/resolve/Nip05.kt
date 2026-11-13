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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Client
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Parser
import com.vitorpamplona.quartz.nip05DnsIdentifiers.OkHttpNip05Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** NIP-05 through Quartz's client: `https://<domain>/.well-known/nostr.json?name=<name>`. */
public class QuartzNip05Resolver(
    http: OkHttpClient,
) : Nip05Resolver {
    private val client = Nip05Client(OkHttpNip05Fetcher({ http }))

    override suspend fun resolve(identifier: String): HexKey? {
        val id = Nip05Id.parse(identifier) ?: throw IllegalArgumentException("Not a NIP-05 identifier: $identifier")
        return client.get(id)?.pubkey?.lowercase()
    }
}

/**
 * NIP-05 against a fixed base URL instead of the name's domain, for tests and for networks
 * that front `.well-known` elsewhere: `<baseUrl>/.well-known/nostr.json?name=<name>`.
 */
public class FixedHostNip05Resolver(
    private val http: OkHttpClient,
    private val baseUrl: String,
) : Nip05Resolver {
    override suspend fun resolve(identifier: String): HexKey? {
        val name = identifier.substringBefore('@')
        val json =
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("${baseUrl.trimEnd('/')}/.well-known/nostr.json?name=$name").build()).execute().use {
                    if (!it.isSuccessful) throw IllegalStateException("NIP-05 lookup failed: HTTP ${it.code}")
                    it.body.string()
                }
            }
        return Nip05Parser().parse(json).names[name]?.lowercase()
    }
}
