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

/** The checksum sidecars Maven repositories serve next to every file. */
public enum class ChecksumAlgorithm(
    public val extension: String,
    public val jdkName: String,
    public val hexLength: Int,
) {
    MD5("md5", "MD5", 32),
    SHA1("sha1", "SHA-1", 40),
    SHA256("sha256", "SHA-256", 64),
    SHA512("sha512", "SHA-512", 128),
    ;

    public companion object {
        public fun fromFileName(name: String): ChecksumAlgorithm? = entries.firstOrNull { name.endsWith("." + it.extension) }
    }
}

/**
 * A request path in a Maven repository, parsed from the end: `…/artifact/version/file`.
 *
 * Everything before the artifact is the group, so a group can have any number of segments; the
 * file name must start with `artifact-version` (or, for a -SNAPSHOT, `artifact-base-timestamp-N`).
 */
public sealed interface MavenPath {
    /** A checksum sidecar of the file this path names, or null for the file itself. */
    public val checksum: ChecksumAlgorithm?

    /** `group/artifact/maven-metadata.xml`: every version of a module. */
    public data class ArtifactMetadata(
        val module: ModuleId,
        override val checksum: ChecksumAlgorithm? = null,
    ) : MavenPath

    /** `group/artifact/1.0-SNAPSHOT/maven-metadata.xml`: a snapshot's timestamped builds. */
    public data class VersionMetadata(
        val coordinate: Coordinate,
        override val checksum: ChecksumAlgorithm? = null,
    ) : MavenPath

    /** A published file: jar, aar, klib, pom, module, sources, javadoc, asc, … */
    public data class ArtifactFile(
        val coordinate: Coordinate,
        /** The file name without any checksum suffix. */
        val fileName: String,
        val classifier: String?,
        /** Everything after the first dot past the version and classifier: `jar`, `jar.asc`. */
        val extension: String,
        /** `20240101.120000-3` for a timestamped snapshot file, else null. */
        val snapshotStamp: String?,
        override val checksum: ChecksumAlgorithm? = null,
    ) : MavenPath {
        /** The key this file has in a manifest: `artifact/version/fileName`. */
        val manifestKey: String get() = "${coordinate.artifact}/${coordinate.version}/$fileName"

        val isSignature: Boolean get() = extension.endsWith(".asc")
    }

    public companion object {
        public const val METADATA_FILE: String = "maven-metadata.xml"

        private val SNAPSHOT_STAMP = Regex("""^(\d{8}\.\d{6}-\d+)""")

        /**
         * Orders snapshot stamps (`20240102.030405-7`) by time, then by build number as a number:
         * build 10 comes after build 9, which a string comparison gets wrong.
         */
        public val SNAPSHOT_STAMP_ORDER: Comparator<String> =
            compareBy<String>({ it.substringBefore('-') }, { it.substringAfter('-').toIntOrNull() ?: 0 })

        public fun parse(rawPath: String): MavenPath? {
            val path = rawPath.substringBefore('?').trim('/')
            if (path.isEmpty()) return null
            val segments = path.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            if (segments.size < 3) return null

            val last = segments.last()
            val checksum = ChecksumAlgorithm.fromFileName(last)
            val base = if (checksum != null) last.removeSuffix("." + checksum.extension) else last

            return runCatching {
                if (base == METADATA_FILE) {
                    val parent = segments[segments.size - 2]
                    if (isSnapshotVersion(parent) && segments.size >= 4) {
                        val group = segments.subList(0, segments.size - 3).joinToString(".")
                        VersionMetadata(Coordinate(group, segments[segments.size - 3], parent), checksum)
                    } else {
                        val group = segments.subList(0, segments.size - 2).joinToString(".")
                        ArtifactMetadata(ModuleId(group, parent), checksum)
                    }
                } else {
                    if (segments.size < 4) return null
                    val version = segments[segments.size - 2]
                    val artifact = segments[segments.size - 3]
                    val group = segments.subList(0, segments.size - 3).joinToString(".")
                    parseFile(Coordinate(group, artifact, version), base, checksum)
                }
            }.getOrNull()
        }

        /** Splits [fileName] of [coordinate] into classifier and extension, or null if it isn't one of its files. */
        public fun parseFile(
            coordinate: Coordinate,
            fileName: String,
            checksum: ChecksumAlgorithm? = null,
        ): ArtifactFile? {
            val prefix = coordinate.artifact + "-"
            if (!fileName.startsWith(prefix)) return null
            val afterArtifact = fileName.substring(prefix.length)

            var stamp: String? = null
            val rest: String =
                when {
                    afterArtifact.startsWith(coordinate.version) -> {
                        afterArtifact.substring(coordinate.version.length)
                    }

                    coordinate.isSnapshot -> {
                        val baseVersion = coordinate.version.removeSuffix("-SNAPSHOT") + "-"
                        if (!afterArtifact.startsWith(baseVersion)) return null
                        val afterBase = afterArtifact.substring(baseVersion.length)
                        val match = SNAPSHOT_STAMP.find(afterBase) ?: return null
                        stamp = match.groupValues[1]
                        afterBase.substring(match.value.length)
                    }

                    else -> {
                        return null
                    }
                }

            val classifier: String?
            val extension: String
            when {
                rest.startsWith("-") -> {
                    val dot = rest.indexOf('.')
                    if (dot <= 1 || dot == rest.length - 1) return null
                    classifier = rest.substring(1, dot)
                    extension = rest.substring(dot + 1)
                }

                rest.startsWith(".") && rest.length > 1 -> {
                    classifier = null
                    extension = rest.substring(1)
                }

                else -> {
                    return null
                }
            }
            return ArtifactFile(coordinate, fileName, classifier, extension, stamp, checksum)
        }
    }
}

/** The repository path of this file or sidecar. */
public fun MavenPath.toPath(): String {
    val suffix = checksum?.let { "." + it.extension } ?: ""
    return when (this) {
        is MavenPath.ArtifactMetadata -> "${module.path}/${MavenPath.METADATA_FILE}$suffix"
        is MavenPath.VersionMetadata -> "${coordinate.module.path}/${coordinate.version}/${MavenPath.METADATA_FILE}$suffix"
        is MavenPath.ArtifactFile -> "${coordinate.module.path}/${coordinate.version}/$fileName$suffix"
    }
}
