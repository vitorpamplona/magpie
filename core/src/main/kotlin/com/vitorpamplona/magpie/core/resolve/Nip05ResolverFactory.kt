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

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.IElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinCoreRpcClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinCoreRpcConfig
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URI

/** The single resolver factory used by dependency resolution and publication checks. */
public object Nip05ResolverFactory {
    /**
     * ElectrumX is server-trusting in this MVP, even with several endpoints. No result is described
     * as SPV-verified. RPC credentials come from [environment] and never enter the configuration map.
     */
    public fun create(
        http: OkHttpClient,
        config: Map<String, String>,
        environment: (String) -> String? = { System.getenv(it) },
    ): DispatchingNip05Resolver {
        val https = HttpNip05Resolver(http, config[EngineConfig.NIP05_BASE_URL]?.takeIf { it.isNotBlank() })
        val backend = config[EngineConfig.NAMECOIN_BACKEND]?.trim()?.takeIf { it.isNotEmpty() } ?: EngineConfig.NAMECOIN_BACKEND_DISABLED
        if (backend == EngineConfig.NAMECOIN_BACKEND_DISABLED) return DispatchingNip05Resolver(https, null)

        val timeout =
            config[EngineConfig.NAMECOIN_LOOKUP_TIMEOUT_MS]?.toLongOrNull()
                ?: 20_000L
        require(timeout > 0 && timeout <= Int.MAX_VALUE) { "Namecoin lookup timeout must be between 1 and ${Int.MAX_VALUE} ms" }
        val servers = parseElectrumxServers(config.list(EngineConfig.NAMECOIN_ELECTRUMX_SERVERS))
        val fallback = config[EngineConfig.NAMECOIN_CORE_RPC_FALLBACK_TO_ELECTRUMX]?.toBoolean() == true

        val electrumClient =
            if (backend == EngineConfig.NAMECOIN_BACKEND_ELECTRUMX || fallback) {
                require(servers.isNotEmpty()) { "Namecoin ElectrumX backend needs at least one configured server" }
                ElectrumXClient(connectTimeoutMs = timeout, readTimeoutMs = timeout)
            } else {
                null
            }

        val client: IElectrumXClient =
            when (backend) {
                EngineConfig.NAMECOIN_BACKEND_CORE_RPC -> {
                    val url = config[EngineConfig.NAMECOIN_CORE_RPC_URL]?.trim().orEmpty()
                    val parsed = url.toHttpUrlOrNull()
                    require(parsed != null) { "Namecoin Core RPC needs an http:// or https:// URL" }
                    require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                        "Namecoin Core RPC credentials must use environment variables, not the URL"
                    }
                    val usernameEnv =
                        config[EngineConfig.NAMECOIN_CORE_RPC_USERNAME_ENV]?.takeIf { it.isNotBlank() }
                            ?: EngineConfig.DEFAULT_NAMECOIN_CORE_RPC_USERNAME_ENV
                    val passwordEnv =
                        config[EngineConfig.NAMECOIN_CORE_RPC_PASSWORD_ENV]?.takeIf { it.isNotBlank() }
                            ?: EngineConfig.DEFAULT_NAMECOIN_CORE_RPC_PASSWORD_ENV
                    val core =
                        NamecoinCoreRpcClient { http }.apply {
                            setConfig(
                                NamecoinCoreRpcConfig(
                                    url = url,
                                    username = environment(usernameEnv).orEmpty(),
                                    password = environment(passwordEnv).orEmpty(),
                                    timeoutMs = timeout,
                                ),
                            )
                        }
                    CoreRpcNamecoinClient(core, electrumClient.takeIf { fallback })
                }

                EngineConfig.NAMECOIN_BACKEND_ELECTRUMX -> electrumClient!!

                else -> throw IllegalArgumentException("Unknown Namecoin backend '$backend'")
            }

        val resolver = NamecoinNameResolver(BoundedNamecoinClient(client), lookupTimeoutMs = timeout) { servers }
        return DispatchingNip05Resolver(https, NamecoinNip05Resolver(resolver))
    }
}

internal fun parseElectrumxServers(configured: List<String>): List<ElectrumxServer> =
    configured.map { endpoint ->
        val value = endpoint.trim()
        val uri = URI(value)
        val useSsl =
            when (uri.scheme?.lowercase()) {
                "ssl" -> true
                "tcp" -> false
                else -> throw IllegalArgumentException("Namecoin ElectrumX endpoint must use ssl:// or tcp://: $value")
            }
        require(uri.host != null && uri.port in 1..65_535) { "Namecoin ElectrumX endpoint needs a host and port: $value" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) {
            "Namecoin ElectrumX endpoint must contain only scheme, host, and port: $value"
        }
        ElectrumxServer(uri.host, uri.port, useSsl = useSsl)
    }

private fun Map<String, String>.list(key: String): List<String> =
    this[key]
        .orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
