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

import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * `magpie { … }` in `settings.gradle.kts`:
 * ```
 * magpie {
 *     relays("wss://relay.example.com")
 *     group("com.example") { publisher("npub1…") }   // or { nip05() } for trust on first use
 * }
 * ```
 */
abstract class MagpieExtension
    @Inject
    constructor() {
        /** Relays to read release events from. Results from all of them are merged. */
        abstract val relays: ListProperty<String>

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

        /** Testing hook: resolve NIP-05 names against this base URL instead of their domain. */
        abstract val nip05BaseUrl: Property<String>

        internal val groups = mutableListOf<MagpieGroup>()

        fun relays(vararg urls: String) {
            relays.addAll(urls.toList())
        }

        fun fallbackServers(vararg urls: String) {
            fallbackServers.addAll(urls.toList())
        }

        /** Trusts [group] and its subgroups to one publisher, configured by [action]. */
        fun group(
            group: String,
            action: Action<MagpieGroup>,
        ) {
            val spec = MagpieGroup(group)
            action.execute(spec)
            groups.removeAll { it.group == group }
            groups += spec
        }
    }

/** How one group's publisher is trusted. */
class MagpieGroup(
    val group: String,
) {
    internal var pin: String? = null

    /** Trust exactly this key (`npub1…` or hex). */
    fun publisher(npub: String) {
        pin = npub.trim()
    }

    /**
     * Trust whatever key [identifier] (`name@domain`) points to the first time it is resolved,
     * and pin it in `gradle/magpie.lock`; any later change fails the build. Without an argument,
     * the group's reversed domain: `com.example.lib` -> `_@lib.example.com`.
     */
    @JvmOverloads
    fun nip05(identifier: String? = null) {
        pin = "nip05:" + (identifier ?: ("_@" + group.split('.').reversed().joinToString(".")))
    }
}
