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
package com.vitorpamplona.magpie.gradle

import com.vitorpamplona.magpie.bridge.EngineConfig
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * `magpie { … }` in `settings.gradle.kts`:
 * ```
 * magpie {
 *     group("com.example")                            // releases@example.com vouches; its nostr.json lists the key's relays
 *     indexRelays("wss://purplepag.es")               // needed for keys no domain lists relays for
 *     group("org.other") { publisher("npub1…") }
 * }
 * ```
 */
abstract class MagpieExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** Namecoin backends for .bit NIP-05 identities. Disabled by default. */
        val namecoin: MagpieNamecoinSpec = MagpieNamecoinSpec(objects)

        /** Configures Namecoin without putting RPC credential values in the build model. */
        fun namecoin(action: Action<MagpieNamecoinSpec>) {
            action.execute(namecoin)
        }

        /**
         * Index relays: where trusted keys' relay lists (kind 10002) are looked up. Each key's
         * releases are read from its own write relays and from the relays its domain's
         * nostr.json lists for it; from these only when a key has neither. Optional when every
         * group is trusted through its domain and the domain lists relays for its key.
         */
        abstract val indexRelays: ListProperty<String>

        /** Blossom servers to try after a release's hints and its publisher's kind 10063. */
        abstract val fallbackServers: ListProperty<String>

        /** `gradle/magpie.lock` by default. */
        abstract val lockFile: RegularFileProperty

        /** Write and honor the lock file (default true). */
        abstract val useLockFile: Property<Boolean>

        /** Fail instead of adding to the lock (for CI). Also `-Pmagpie.frozenLock=true`. */
        abstract val frozenLock: Property<Boolean>

        /** Engine jars, blobs, manifests and events; `~/.gradle/magpie` by default. */
        abstract val cacheDir: DirectoryProperty

        /** How long a relay may stay silent before a query moves on, in milliseconds. */
        abstract val relayTimeoutMillis: Property<Long>

        /** Testing hook: NIP-05 lookups go to this base URL (`{domain}` stands for the domain) instead of `https://<domain>`. */
        abstract val nip05BaseUrl: Property<String>

        internal val groups = mutableListOf<MagpieGroup>()

        fun indexRelays(vararg urls: String) {
            indexRelays.addAll(urls.toList())
        }

        fun fallbackServers(vararg urls: String) {
            fallbackServers.addAll(urls.toList())
        }

        /**
         * Trusts [group] and its subgroups to the key its domain vouches for:
         * `releases@<domain>` from the group's full host down to its registrable domain
         * (`com.example.lib` tries `releases@lib.example.com`, then `releases@example.com`).
         */
        fun group(group: String) {
            group(group) {}
        }

        /** Trusts [group] and its subgroups to one publisher, configured by [action]. */
        fun group(
            group: String,
            action: Action<MagpieGroup>,
        ) {
            val spec = MagpieGroup(group)
            action.execute(spec)
            spec.validate()
            groups.removeAll { it.group == group }
            groups += spec
        }
    }

/** How one group's publisher is trusted. By default, the group's domain vouches for it. */
class MagpieGroup(
    val group: String,
) {
    internal var pin: String = DOMAIN

    /** Trust exactly this key (`npub1…` or hex), with no domain involved. */
    fun publisher(npub: String) {
        pin = npub.trim()
    }

    /**
     * Trust the key a NIP-05 identity on one of the group's domains names, instead of
     * `releases@`: `nip05("maven@example.com")` for `com.example`. Without an argument, the
     * default (`releases@` down the domain chain).
     */
    @JvmOverloads
    fun nip05(identifier: String? = null) {
        pin = if (identifier == null) DOMAIN else "nip05:" + identifier.trim().lowercase()
    }

    /** Explicitly trusts a Namecoin identity, including for a legacy DNS-based Maven group. */
    fun namecoin(identifier: String) {
        pin = "namecoin:" + identifier.trim().lowercase()
    }

    /** Catches the obvious misconfiguration early; the engine checks the public suffix list too. */
    internal fun validate() {
        if (pin.startsWith("namecoin:")) {
            val domain = pin.substringAfter('@', "")
            require(domain.endsWith(".bit")) {
                "Magpie: namecoin(...) needs an identity such as releases@example.bit"
            }
            return
        }
        if (!pin.startsWith("nip05:")) return
        val domain = pin.substringAfter('@', "")
        val host =
            group
                .lowercase()
                .split('.')
                .reversed()
                .joinToString(".")
        require(domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))) {
            "Magpie: ${pin.removePrefix("nip05:")} can't vouch for $group; use an identity on $host or one of its parent domains"
        }
    }

    internal companion object {
        const val DOMAIN = "domain"
    }
}

/** Shared Namecoin connection policy for consumers and publishers. */
class MagpieNamecoinSpec internal constructor(
    objects: ObjectFactory,
) {
    /** `disabled`, `coreRpc`, or `electrumx`. ElectrumX is server-trusting, not SPV-verified. */
    val backend: Property<String> =
        objects.property(String::class.java).convention(EngineConfig.NAMECOIN_BACKEND_DISABLED)

    /** Namecoin Core JSON-RPC endpoint. Do not put credentials in this URL. */
    val coreRpcUrl: Property<String> = objects.property(String::class.java)

    /** Environment variable names, never the RPC credential values. */
    val coreRpcUsernameEnv: Property<String> =
        objects.property(String::class.java).convention(EngineConfig.DEFAULT_NAMECOIN_CORE_RPC_USERNAME_ENV)
    val coreRpcPasswordEnv: Property<String> =
        objects.property(String::class.java).convention(EngineConfig.DEFAULT_NAMECOIN_CORE_RPC_PASSWORD_ENV)

    /** Explicit ElectrumX endpoints, for example `ssl://electrumx.example:50002`. */
    val electrumxServers: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Overall lookup and traversal timeout. */
    val lookupTimeoutMillis: Property<Long> = objects.property(Long::class.java).convention(20_000L)

    /** If Core RPC is unreachable, explicitly allow the configured server-trusting ElectrumX fallback. */
    val coreRpcFallbackToElectrumx: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    fun electrumxServers(vararg endpoints: String) {
        electrumxServers.addAll(endpoints.toList())
    }

    internal fun engineConfig(): Map<String, String> =
        buildMap {
            put(EngineConfig.NAMECOIN_BACKEND, backend.get())
            coreRpcUrl.orNull?.takeIf { it.isNotBlank() }?.let {
                put(EngineConfig.NAMECOIN_CORE_RPC_URL, EngineConfig.validateNamecoinCoreRpcUrl(it))
            }
            put(EngineConfig.NAMECOIN_CORE_RPC_USERNAME_ENV, coreRpcUsernameEnv.get())
            put(EngineConfig.NAMECOIN_CORE_RPC_PASSWORD_ENV, coreRpcPasswordEnv.get())
            put(EngineConfig.NAMECOIN_ELECTRUMX_SERVERS, electrumxServers.get().joinToString("\n"))
            put(EngineConfig.NAMECOIN_LOOKUP_TIMEOUT_MS, lookupTimeoutMillis.get().toString())
            put(EngineConfig.NAMECOIN_CORE_RPC_FALLBACK_TO_ELECTRUMX, coreRpcFallbackToElectrumx.get().toString())
        }
}
