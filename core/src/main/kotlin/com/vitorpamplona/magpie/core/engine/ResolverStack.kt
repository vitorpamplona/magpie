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
package com.vitorpamplona.magpie.core.engine

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.core.nostr.NostrIO
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.resolve.BlobFetcher
import com.vitorpamplona.magpie.core.resolve.BlobStore
import com.vitorpamplona.magpie.core.resolve.ChecksumFiles
import com.vitorpamplona.magpie.core.resolve.FixedHostNip05Resolver
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.LockStore
import com.vitorpamplona.magpie.core.resolve.QuartzNip05Resolver
import com.vitorpamplona.magpie.core.resolve.RelayQuery
import com.vitorpamplona.magpie.core.resolve.RepositoryMirror
import com.vitorpamplona.magpie.core.resolve.Resolver
import com.vitorpamplona.magpie.core.resolve.ResolverSettings
import com.vitorpamplona.magpie.core.resolve.Router
import com.vitorpamplona.magpie.core.resolve.TrustPolicy
import com.vitorpamplona.magpie.core.util.MagpieLog
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

public object Http {
    public fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
}

/** Everything resolution needs, wired from a configuration map; shared by the plugin and the gateway. */
public class ResolverStack(
    public val resolver: Resolver,
    public val router: Router,
    public val mirror: RepositoryMirror,
    public val offline: Boolean,
    private val nostr: NostrIO?,
    private val http: OkHttpClient,
) : AutoCloseable {
    override fun close() {
        nostr?.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    public companion object {
        /** "false" to skip the offline mirror (the gateway has no use for it). */
        public const val MIRROR: String = "mirror"

        /** Keys in [EngineConfig]; the extra `nip05BaseUrl` points NIP-05 lookups at a fixed host. */
        public fun create(
            config: Map<String, String>,
            log: MagpieLog,
            nostrOverride: NostrIO? = null,
        ): ResolverStack {
            val offline = config.flag(EngineConfig.OFFLINE)
            val cacheDir = File(config[EngineConfig.CACHE_DIR] ?: File(System.getProperty("user.home"), ".gradle/magpie").path)
            val http = Http.client()
            val relays = config.list(EngineConfig.RELAYS)
            val nostr =
                if (offline) {
                    null
                } else {
                    nostrOverride
                        ?: QuartzNostrIO(http, log, idleTimeoutMs = config[EngineConfig.RELAY_TIMEOUT_MS]?.toLongOrNull() ?: 10_000)
                }
            val settings =
                ResolverSettings(
                    relays = relays,
                    fallbackServers = config.list(EngineConfig.FALLBACK_SERVERS),
                    cacheDir = cacheDir,
                    offline = offline,
                    negativeTtlMs = config[EngineConfig.NEGATIVE_TTL_MS]?.toLongOrNull() ?: 30_000,
                )
            val lock =
                LockStore(
                    config[EngineConfig.LOCK_FILE]?.takeIf { it.isNotBlank() }?.let(::File),
                    config.flag(EngineConfig.LOCK_FROZEN),
                    log,
                )
            val nip05 =
                config["nip05BaseUrl"]?.takeIf { it.isNotBlank() }?.let { FixedHostNip05Resolver(http, it) } ?: QuartzNip05Resolver(http)
            val trust = TrustPolicy(config.list(EngineConfig.GROUPS).map { GroupRule.parse(it) }, lock, nip05, offline, log)
            val store = BlobStore(File(cacheDir, "blobs"))
            val resolver = Resolver(settings, trust, RelayQuery(nostr, relays, cacheDir, log), BlobFetcher(http, store, log), log)
            val mirror = RepositoryMirror(File(cacheDir, "mirror"))
            val useMirror = config[MIRROR]?.toBoolean() ?: true
            val router = Router(resolver, ChecksumFiles(File(cacheDir, "checksums")), log, mirror.takeIf { useMirror })
            return ResolverStack(resolver, router, mirror, offline, nostr, http)
        }
    }
}
