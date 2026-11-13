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

/**
 * Which NIP-05 identities may vouch for a group's release key, the way Maven Central ties a
 * namespace to a domain.
 *
 * A group is a reversed domain. Its candidates are `releases@<domain>` for every domain from the
 * group's full host down to its registrable domain: `com.vitorpamplona.quartz` gives
 * `releases@quartz.vitorpamplona.com`, then `releases@vitorpamplona.com`. The first that answers
 * wins, so an organisation can sign everything with one key and a team can take a subdomain.
 * `releases` rather than `_`, because `_` is usually the domain's general identity.
 *
 * Whether a host is registrable needs the public suffix list, which [registrableDomain] supplies
 * (it returns the registrable domain of a host, or null if the host is itself a public suffix).
 */
public object ReleaseIdentity {
    /** The NIP-05 name that holds a domain's release key. */
    public const val NAME: String = MagpieSpec.RELEASES_NIP05_NAME

    /** `com.Example.lib` -> `lib.example.com`, or null if a segment can't be a DNS label. */
    public fun hostOf(group: String): String? {
        val labels = group.lowercase().split('.').reversed()
        if (labels.size < 2 || labels.any { !isDnsLabel(it) }) return null
        return labels.joinToString(".")
    }

    /** The domains whose `releases@` may vouch for [group], most specific first. Empty if none can. */
    public fun domains(
        group: String,
        registrableDomain: (host: String) -> String?,
    ): List<String> {
        val host = hostOf(group) ?: return emptyList()
        val root = registrableDomain(host) ?: return emptyList()
        if (host != root && !host.endsWith(".$root")) return emptyList()
        val out = mutableListOf<String>()
        var current = host
        while (true) {
            out += current
            if (current == root) break
            current = current.substringAfter('.')
        }
        return out
    }

    /** The NIP-05 identifiers to try for [group], most specific first. */
    public fun candidates(
        group: String,
        registrableDomain: (host: String) -> String?,
    ): List<String> = domains(group, registrableDomain).map { "$NAME@$it" }

    /** Whether an explicitly configured identifier `name@domain` belongs to [group]'s domains. */
    public fun belongsTo(
        identifier: String,
        group: String,
        registrableDomain: (host: String) -> String?,
    ): Boolean {
        val domain = identifier.substringAfter('@', "").lowercase()
        return domain.isNotEmpty() && domain in domains(group, registrableDomain)
    }

    private fun isDnsLabel(label: String): Boolean =
        label.isNotEmpty() &&
            label.length <= 63 &&
            !label.startsWith("-") &&
            !label.endsWith("-") &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}
