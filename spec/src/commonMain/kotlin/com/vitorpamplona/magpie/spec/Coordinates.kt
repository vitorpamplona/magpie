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

/** A Maven module: `groupId:artifactId`, version-less. */
public data class ModuleId(
    val group: String,
    val artifact: String,
) {
    init {
        require(isValidGroup(group)) { "Invalid groupId: '$group'" }
        require(isValidSegment(artifact)) { "Invalid artifactId: '$artifact'" }
    }

    /** The value of the indexed module tag. */
    val key: String get() = "$group:$artifact"

    /** `com/example/lib`. */
    val path: String get() = group.replace('.', '/') + "/" + artifact

    public fun withVersion(version: String): Coordinate = Coordinate(group, artifact, version)

    override fun toString(): String = key

    public companion object {
        public fun parse(key: String): ModuleId {
            val parts = key.split(':')
            require(parts.size == 2) { "Expected groupId:artifactId, got '$key'" }
            return ModuleId(parts[0], parts[1])
        }

        public fun parseOrNull(key: String): ModuleId? = runCatching { parse(key) }.getOrNull()
    }
}

/** A Maven coordinate: `groupId:artifactId:version`. */
public data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    init {
        require(isValidGroup(group)) { "Invalid groupId: '$group'" }
        require(isValidSegment(artifact)) { "Invalid artifactId: '$artifact'" }
        require(isValidSegment(version)) { "Invalid version: '$version'" }
    }

    val module: ModuleId get() = ModuleId(group, artifact)

    val isSnapshot: Boolean get() = isSnapshotVersion(version)

    override fun toString(): String = "$group:$artifact:$version"

    public companion object {
        public fun parse(value: String): Coordinate {
            val parts = value.split(':')
            require(parts.size == 3) { "Expected groupId:artifactId:version, got '$value'" }
            return Coordinate(parts[0], parts[1], parts[2])
        }

        public fun parseOrNull(value: String): Coordinate? = runCatching { parse(value) }.getOrNull()
    }
}

public fun isSnapshotVersion(version: String): Boolean = version.endsWith("-SNAPSHOT")

/** A path segment Maven accepts as an artifactId or a version: no separators, no traversal. */
public fun isValidSegment(value: String): Boolean =
    value.isNotEmpty() &&
        value != "." &&
        value != ".." &&
        value.length <= 200 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' || it == '+' }

public fun isValidGroup(value: String): Boolean = value.isNotEmpty() && value.split('.').all { isValidSegment(it) }
