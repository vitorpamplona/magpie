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
package com.vitorpamplona.magpie.core.publish

import com.vitorpamplona.magpie.core.util.Hashing
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.ManifestFile
import com.vitorpamplona.magpie.spec.MavenPath
import java.io.File

/** A release on disk: its manifest and where each entry's bytes are. */
public class ScannedRelease(
    public val manifest: Manifest,
    public val files: Map<String, File>,
)

/**
 * Reads what `maven-publish` wrote to a local repository (`build/magpie-repo`) for one group and
 * version. Gradle's checksum sidecars and `maven-metadata.xml` are skipped (resolvers regenerate
 * them); `.asc` signatures are kept. Of a snapshot, only the newest timestamped build is kept.
 */
public object RepoScanner {
    public fun scan(
        repoDir: File,
        group: String,
        version: String,
        rootArtifact: String,
        log: MagpieLog = MagpieLog.NONE,
    ): ScannedRelease {
        val groupDir = File(repoDir, group.replace('.', '/'))
        require(groupDir.isDirectory) { "Nothing was published to $groupDir. Did maven-publish run?" }
        val found = mutableMapOf<String, File>()
        groupDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { artifactDir ->
            val versionDir = File(artifactDir, version)
            if (!versionDir.isDirectory) return@forEach
            val coordinate = runCatching { Coordinate(group, artifactDir.name, version) }.getOrNull() ?: return@forEach
            val parsed =
                versionDir.listFiles()?.filter { it.isFile }?.mapNotNull { file ->
                    val name = file.name
                    when {
                        name.startsWith(MavenPath.METADATA_FILE) -> null

                        ChecksumAlgorithm.fromFileName(name) != null -> null

                        else -> {
                            val path = MavenPath.parseFile(coordinate, name)
                            if (path == null) log.warn("Skipping $file: not a file of $coordinate")
                            path?.let { it to file }
                        }
                    }
                } ?: emptyList()
            val newestStamp = parsed.mapNotNull { it.first.snapshotStamp }.maxOrNull()
            parsed
                .filter { (path, _) ->
                    !coordinate.isSnapshot || newestStamp == null || path.snapshotStamp == null ||
                        path.snapshotStamp == newestStamp
                }.forEach { (path, file) -> found[path.manifestKey] = file }
        }
        require(found.isNotEmpty()) { "No files for version $version under $groupDir" }
        val manifest =
            Manifest(
                Coordinate(group, rootArtifact, version),
                found.mapValues { (_, file) -> ManifestFile(Hashing.sha256(file), file.length()) },
            )
        return ScannedRelease(manifest, found)
    }

    /** The artifactId of the root publication when none is configured: the shortest one. */
    public fun guessRootArtifact(
        repoDir: File,
        group: String,
        version: String,
    ): String {
        val groupDir = File(repoDir, group.replace('.', '/'))
        return groupDir
            .listFiles()
            ?.filter { File(it, version).isDirectory }
            ?.map { it.name }
            ?.minWithOrNull(compareBy({ it.length }, { it }))
            ?: throw IllegalArgumentException("Nothing published under $groupDir for $version")
    }
}
