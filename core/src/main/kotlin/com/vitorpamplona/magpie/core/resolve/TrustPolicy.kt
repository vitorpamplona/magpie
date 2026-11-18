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

import com.vitorpamplona.magpie.core.util.Clock
import com.vitorpamplona.magpie.core.util.ExpiringCache
import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.core.util.StripedMutex
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.LockedPublisher
import com.vitorpamplona.magpie.spec.MagpieReleaseEvent
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.magpie.spec.ReleaseIdentity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How a group's publisher is trusted. */
public sealed interface Pin {
    /** A key written in the build's settings: no domain is asked. */
    public data class Key(
        val pubKey: HexKey,
    ) : Pin

    /** A configured NIP-05 identity, which must sit on one of the group's domains. */
    public data class Nip05(
        val identifier: String,
    ) : Pin

    /** The default: `releases@<domain>` down the group's domain chain ([ReleaseIdentity]). */
    public data object Domain : Pin

    public companion object {
        public const val DOMAIN: String = "domain"

        /** `npub1…`, hex, `nip05:name@domain`, or `domain`. */
        public fun parse(value: String): Pin =
            when {
                value == DOMAIN -> Domain
                value.startsWith("nip05:") -> Nip05(value.removePrefix("nip05:").trim().lowercase())
                else -> Key(PublicKeys.parse(value))
            }
    }
}

/** `group` and its subgroups are trusted from [pin]. */
public data class GroupRule(
    val group: String,
    val pin: Pin,
) {
    public fun matches(candidate: String): Boolean = candidate == group || candidate.startsWith("$group.")

    public companion object {
        /** `com.example=npub1…` */
        public fun parse(line: String): GroupRule {
            val idx = line.indexOf('=')
            require(idx > 0) { "Expected <group>=<pin>, got '$line'" }
            return GroupRule(line.substring(0, idx).trim(), Pin.parse(line.substring(idx + 1).trim()))
        }
    }
}

/** The publisher a lookup trusts for a group, and the rule that pinned it. */
public data class TrustedPublisher(
    val pubKey: HexKey,
    val rule: GroupRule,
)

/** A trust failure that must stop the build: a key changed, or a locked release is gone. */
public class MagpieSecurityException(
    message: String,
) : Exception(message)

/** Reads and writes `gradle/magpie.lock`, one writer at a time. */
public class LockStore(
    private val file: File?,
    private val frozen: Boolean,
    private val log: MagpieLog,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: LockFile? = null

    public val enabled: Boolean get() = file != null

    public fun current(): LockFile {
        cached?.let { return it }
        val loaded = if (file != null && file.isFile) LockFile.parse(file.readText()) else LockFile()
        cached = loaded
        return loaded
    }

    public suspend fun update(change: (LockFile) -> LockFile) {
        if (file == null) return
        mutex.withLock {
            val before = LockFile.parse(if (file.isFile) file.readText() else "")
            val after = change(before)
            if (after == before) {
                cached = after
                return
            }
            if (frozen) {
                throw MagpieSecurityException(
                    "magpie.lock is frozen but resolution needs to add to it:\n" +
                        after
                            .render()
                            .lines()
                            .filter { it !in before.render().lines() }
                            .joinToString("\n") +
                        "\nRun the build once without frozen locks and commit ${file.name}.",
                )
            }
            Files2.writeAtomically(file, after.render().toByteArray())
            cached = after
            log.info("Updated ${file.path}")
        }
    }
}

/**
 * Maps a group to the one key whose events count for it; the most specific rule wins.
 *
 * Unless the build names a key, the group's domain vouches for it: `releases@<domain>` from the
 * group's full host down to its registrable domain ([ReleaseIdentity]). The first answer is
 * written to the lock. Every daemon walks the chain again once: a different key fails loudly; a
 * chain it can't decide (a domain unreachable) falls back to the lock, or fails without one.
 */
public class TrustPolicy(
    public val rules: List<GroupRule>,
    private val lock: LockStore,
    private val nip05: Nip05Resolver,
    private val offline: Boolean,
    private val log: MagpieLog,
    private val registrableDomain: (String) -> String? = PublicSuffix::registrableDomain,
    private val clock: Clock = Clock.SYSTEM,
    /** How long a failed lookup is remembered, so parallel requests don't each repeat it. */
    private val failureTtlMs: Long = 30_000,
    /** Receives the relays a vouching domain lists for its key: where that key's events are. */
    private val relayHints: RelayHints? = null,
) {
    private val verified = ConcurrentHashMap<String, HexKey>()
    private val failures = ExpiringCache<String, MagpieSecurityException>(1_000, clock)

    // One lookup per group at a time; groups don't wait for each other.
    private val locks = StripedMutex()

    init {
        rules.forEach { rule ->
            when (val pin = rule.pin) {
                is Pin.Nip05 -> {
                    require(ReleaseIdentity.belongsTo(pin.identifier, rule.group, registrableDomain)) {
                        "${pin.identifier} can't vouch for ${rule.group}: its domain must be one of " +
                            ReleaseIdentity.domains(rule.group, registrableDomain).ifEmpty { listOf("(none: the group is not a domain)") }.joinToString()
                    }
                }

                Pin.Domain -> {
                    require(identifiersFor(rule).isNotEmpty()) {
                        "${rule.group} doesn't map to a domain that could vouch for its publisher; configure publisher(\"npub1…\") for it"
                    }
                }

                is Pin.Key -> {}
            }
        }
    }

    public fun ruleFor(group: String): GroupRule? = rules.filter { it.matches(group) }.maxByOrNull { it.group.length }

    /** The NIP-05 identities that may vouch for [rule]'s group, most specific first. */
    public fun identifiersFor(rule: GroupRule): List<String> =
        when (val pin = rule.pin) {
            is Pin.Nip05 -> listOf(pin.identifier)
            Pin.Domain -> ReleaseIdentity.candidates(rule.group, registrableDomain)
            is Pin.Key -> emptyList()
        }

    public suspend fun publisherFor(group: String): TrustedPublisher? {
        val rule = ruleFor(group) ?: return null
        return when (val pin = rule.pin) {
            is Pin.Key -> TrustedPublisher(pin.pubKey, rule)
            else -> TrustedPublisher(vouchedKey(rule), rule)
        }
    }

    private suspend fun vouchedKey(rule: GroupRule): HexKey {
        verified[rule.group]?.let { return it }
        failures.get(rule.group)?.let { throw it }
        return locks.forKey(rule.group).withLock {
            verified[rule.group]?.let { return it }
            failures.get(rule.group)?.let { throw it }
            try {
                decideKey(rule)
            } catch (e: MagpieSecurityException) {
                failures.put(rule.group, e, failureTtlMs)
                throw e
            }
        }
    }

    private suspend fun decideKey(rule: GroupRule): HexKey {
        run {
            val identifiers = identifiersFor(rule)
            val locked = lock.current().publishers[rule.group]
            if (offline) {
                val key = locked?.pubKey ?: throw MagpieSecurityException("--offline, and magpie.lock has no key for ${rule.group} yet: resolve once online.")
                verified[rule.group] = key
                return key
            }
            val key =
                when (val answer = nip05.vouch(identifiers)) {
                    is VouchedKey.Found -> {
                        when {
                            locked == null -> {
                                lock.update { it.withPublisher(rule.group, LockedPublisher(answer.pubKey, "nip05:${answer.identifier}")) }
                                log.lifecycle("Magpie: trusting ${PublicKeys.npub(answer.pubKey)} for ${rule.group} (${answer.identifier}, recorded in magpie.lock)")
                            }

                            locked.pubKey != answer.pubKey -> {
                                throw MagpieSecurityException(
                                    "PUBLISHER KEY CHANGED for ${rule.group}: ${answer.identifier} now names ${PublicKeys.npub(answer.pubKey)}, " +
                                        "but magpie.lock trusts ${PublicKeys.npub(locked.pubKey)} (${locked.source}). Refusing to resolve. " +
                                        "If the publisher really rotated keys, verify it out of band and delete the 'publisher ${rule.group}' line from magpie.lock.",
                                )
                            }

                            locked.source != "nip05:${answer.identifier}" -> {
                                // Same key, now vouched for by another identity on the chain.
                                lock.update { it.withPublisher(rule.group, LockedPublisher(answer.pubKey, "nip05:${answer.identifier}")) }
                            }
                        }
                        relayHints?.put(answer.pubKey, answer.relays)
                        answer.pubKey
                    }

                    is VouchedKey.Undecided -> {
                        if (locked == null) {
                            throw MagpieSecurityException(
                                "Could not read ${answer.identifier} (${answer.reason}), so the release key for ${rule.group} can't be decided, " +
                                    "and magpie.lock has none yet.",
                            )
                        }
                        log.warn("Could not read ${answer.identifier} (${answer.reason}); using the key magpie.lock trusts for ${rule.group}")
                        locked.pubKey
                    }

                    is VouchedKey.NoneListed -> {
                        if (locked == null) {
                            throw MagpieSecurityException(
                                "No release key for ${rule.group}: none of ${answer.tried.joinToString()} is listed. The publisher adds " +
                                    "{\"names\":{\"${ReleaseIdentity.NAME}\":\"<hex pubkey>\"}} to /.well-known/nostr.json on one of those domains; " +
                                    "or pin a key with publisher(\"npub1…\").",
                            )
                        }
                        log.warn("${rule.group}'s domains no longer list a release key; using the one magpie.lock trusts")
                        locked.pubKey
                    }
                }
            verified[rule.group] = key
            return key
        }
    }

    /** The manifest the lock pins for [coordinate], if any. */
    public fun lockedManifest(coordinate: Coordinate): String? = lock.current().releaseFor(coordinate)?.manifestSha256

    public suspend fun recordRelease(
        coordinate: Coordinate,
        release: MagpieReleaseEvent,
    ) {
        if (!lock.enabled) return
        val manifest = release.manifest()?.sha256 ?: return
        if (lock.current().releaseFor(coordinate)?.manifestSha256 == manifest) return
        lock.update { it.withRelease(coordinate, manifest, release.id) }
    }
}
