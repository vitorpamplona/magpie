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

import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.LockedPublisher
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** How a group's publisher is trusted. */
public sealed interface Pin {
    /** A key written in the build's settings. */
    public data class Key(
        val pubKey: HexKey,
    ) : Pin

    /** A NIP-05 name resolved once (trust on first use) and recorded in the lock. */
    public data class Nip05(
        val identifier: String,
    ) : Pin

    public companion object {
        /** `npub1…`, hex, or `nip05:name@domain`. */
        public fun parse(value: String): Pin =
            if (value.startsWith("nip05:")) {
                Nip05(value.removePrefix("nip05:").trim().lowercase())
            } else {
                Key(PublicKeys.parse(value))
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

/** Resolves NIP-05 names to keys. */
public fun interface Nip05Resolver {
    public suspend fun resolve(identifier: String): HexKey?
}

/**
 * Maps a group to the one key whose events count for it. The most specific rule wins. NIP-05
 * pins resolve once and are written to the lock; each daemon re-checks the name once and fails
 * loudly if it now points somewhere else.
 */
public class TrustPolicy(
    public val rules: List<GroupRule>,
    private val lock: LockStore,
    private val nip05: Nip05Resolver,
    private val offline: Boolean,
    private val log: MagpieLog,
) {
    private val verified = mutableMapOf<String, HexKey>()
    private val mutex = Mutex()

    public fun ruleFor(group: String): GroupRule? = rules.filter { it.matches(group) }.maxByOrNull { it.group.length }

    public suspend fun publisherFor(group: String): TrustedPublisher? {
        val rule = ruleFor(group) ?: return null
        return when (val pin = rule.pin) {
            is Pin.Key -> TrustedPublisher(pin.pubKey, rule)
            is Pin.Nip05 -> TrustedPublisher(nip05Key(rule, pin), rule)
        }
    }

    private suspend fun nip05Key(
        rule: GroupRule,
        pin: Pin.Nip05,
    ): HexKey =
        mutex.withLock {
            verified[rule.group]?.let { return it }
            val source = "nip05:${pin.identifier}"
            val locked = lock.current().publishers[rule.group]
            if (locked != null && locked.source != source) {
                throw MagpieSecurityException(
                    "magpie.lock pins ${rule.group} to ${locked.source} (${PublicKeys.npub(
                        locked.pubKey,
                    )}), but the build now says $source. " +
                        "If that change is intended, delete the 'publisher ${rule.group}' line from magpie.lock.",
                )
            }
            val resolved =
                if (offline) {
                    null
                } else {
                    try {
                        nip05.resolve(pin.identifier)
                    } catch (e: Exception) {
                        log.warn("Could not resolve NIP-05 ${pin.identifier}: ${e.message}")
                        null
                    }
                }
            val key =
                when {
                    locked != null && resolved != null && resolved != locked.pubKey -> {
                        throw MagpieSecurityException(
                            "PUBLISHER KEY CHANGED for ${rule.group}: ${pin.identifier} now points to ${PublicKeys.npub(resolved)}, " +
                                "but magpie.lock trusted ${PublicKeys.npub(locked.pubKey)} on first use. Refusing to resolve. " +
                                "If the publisher really rotated keys, verify it out of band and delete the 'publisher ${rule.group}' line from magpie.lock.",
                        )
                    }

                    locked != null -> {
                        locked.pubKey
                    }

                    resolved != null -> {
                        lock.update { it.withPublisher(rule.group, LockedPublisher(resolved, source)) }
                        log.lifecycle(
                            "Magpie: trusting ${PublicKeys.npub(resolved)} for ${rule.group} (${pin.identifier}, recorded in magpie.lock)",
                        )
                        resolved
                    }

                    else -> {
                        throw MagpieSecurityException(
                            "Could not resolve NIP-05 ${pin.identifier} for ${rule.group}, and magpie.lock has no key for it yet.",
                        )
                    }
                }
            verified[rule.group] = key
            key
        }

    /** The release id the lock pins for [coordinate], if any. */
    public fun lockedRelease(coordinate: Coordinate): String? = lock.current().releaseFor(coordinate)

    public suspend fun recordRelease(
        coordinate: Coordinate,
        eventId: String,
    ) {
        if (!lock.enabled) return
        if (lock.current().releaseFor(coordinate) == eventId) return
        lock.update { it.withRelease(coordinate, eventId) }
    }
}
