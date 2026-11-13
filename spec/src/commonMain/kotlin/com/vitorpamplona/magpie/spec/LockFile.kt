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

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/** A publisher key the lock pins for a group, and where it came from (`nip05:releases@domain`). */
public data class LockedPublisher(
    val pubKey: HexKey,
    val source: String,
)

/** The manifest a resolved version pinned, and (for information) the event that carried it. */
public data class LockedRelease(
    val manifestSha256: String,
    val eventId: String? = null,
)

/**
 * `gradle/magpie.lock`: what the first resolution learned, so later ones can't silently change.
 *
 * - `publisher <group> <hex pubkey> <source>`: the key the group's domain vouched for
 *   (`releases@<domain>` or a configured NIP-05). A different key later fails the build.
 * - `release <group:artifact:version> <manifest sha256> [<event id>]`: the files resolution
 *   used. Later resolutions accept only a release of that exact manifest: a backdated release
 *   can't swap the files, and a rotated key that re-signs the same manifest still matches.
 *
 * The text form is sorted and stable, so the file diffs cleanly in version control.
 */
public data class LockFile(
    val publishers: Map<String, LockedPublisher> = emptyMap(),
    val releases: Map<String, LockedRelease> = emptyMap(),
) {
    public fun withPublisher(
        group: String,
        publisher: LockedPublisher,
    ): LockFile = copy(publishers = publishers + (group to publisher))

    public fun withRelease(
        coordinate: Coordinate,
        manifestSha256: String,
        eventId: String? = null,
    ): LockFile = copy(releases = releases + (coordinate.toString() to LockedRelease(manifestSha256, eventId)))

    public fun releaseFor(coordinate: Coordinate): LockedRelease? = releases[coordinate.toString()]

    public fun render(): String =
        buildString {
            append(HEADER)
            publishers.keys.sorted().forEach { group ->
                val p = publishers.getValue(group)
                append("publisher $group ${p.pubKey} ${p.source}\n")
            }
            releases.keys.sorted().forEach { coordinate ->
                val r = releases.getValue(coordinate)
                append("release $coordinate ${r.manifestSha256}")
                r.eventId?.let { append(" ").append(it) }
                append("\n")
            }
        }

    public companion object {
        public const val HEADER: String =
            "# Magpie lock file. Commit it: it pins the key each group's domain vouched for and the\n" +
                "# manifest each resolved version came from. A change to either fails the build.\n" +
                "version 1\n"

        public fun parse(text: String): LockFile {
            val publishers = mutableMapOf<String, LockedPublisher>()
            val releases = mutableMapOf<String, LockedRelease>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "version" -> {
                        require(parts.getOrNull(1) == "1") { "magpie.lock line ${index + 1}: unsupported lock version ${parts.getOrNull(1)}" }
                    }

                    "publisher" -> {
                        require(parts.size >= 3 && isSha256Hex(parts[2])) { "magpie.lock line ${index + 1}: malformed publisher line" }
                        publishers[parts[1]] = LockedPublisher(parts[2], parts.getOrElse(3) { "npub" })
                    }

                    "release" -> {
                        require(
                            parts.size in 3..4 &&
                                Coordinate.parseOrNull(parts[1]) != null &&
                                isSha256Hex(parts[2]) &&
                                (parts.size == 3 || isSha256Hex(parts[3])),
                        ) { "magpie.lock line ${index + 1}: malformed release line" }
                        releases[parts[1]] = LockedRelease(parts[2], parts.getOrNull(3))
                    }

                    else -> {
                        throw IllegalArgumentException("magpie.lock line ${index + 1}: unknown entry '${parts[0]}'")
                    }
                }
            }
            return LockFile(publishers, releases)
        }
    }
}
