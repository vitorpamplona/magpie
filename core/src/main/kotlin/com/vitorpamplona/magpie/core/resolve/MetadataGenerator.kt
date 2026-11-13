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

import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.MavenPath
import com.vitorpamplona.magpie.spec.ModuleId
import com.vitorpamplona.magpie.spec.isSnapshotVersion
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One version as `maven-metadata.xml` lists it. */
public data class VersionEntry(
    val version: String,
    /** Unix seconds the version was published (the winning event's `created_at`). */
    val publishedAt: Long,
    val yanked: Boolean = false,
)

/**
 * Writes `maven-metadata.xml` the way Maven Central formats it (two-space indent, trailing
 * newline), from events instead of a directory listing. `lastUpdated` is the newest event's
 * `created_at`, so the same events always produce the same bytes.
 */
public object MetadataGenerator {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    private val SNAPSHOT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC)

    public fun formatTimestamp(unixSeconds: Long): String = TIMESTAMP.format(Instant.ofEpochSecond(unixSeconds))

    /**
     * Module-level metadata. [versions] in publication order. `latest` is the newest version of
     * any kind and `release` the newest non-snapshot; yanked versions stay listed (they still
     * resolve) but are never `latest` or `release`.
     */
    public fun artifactMetadata(
        module: ModuleId,
        versions: List<VersionEntry>,
    ): String {
        val ordered = versions.sortedWith(compareBy<VersionEntry>({ it.publishedAt })).distinctBy { it.version }
        val live = ordered.filter { !it.yanked }
        val latest = live.lastOrNull()?.version
        val release = live.lastOrNull { !isSnapshotVersion(it.version) }?.version
        val lastUpdated = ordered.maxOfOrNull { it.publishedAt }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<metadata>\n")
            append("  <groupId>").append(xml(module.group)).append("</groupId>\n")
            append("  <artifactId>").append(xml(module.artifact)).append("</artifactId>\n")
            append("  <versioning>\n")
            latest?.let { append("    <latest>").append(xml(it)).append("</latest>\n") }
            release?.let { append("    <release>").append(xml(it)).append("</release>\n") }
            append("    <versions>\n")
            ordered.forEach { append("      <version>").append(xml(it.version)).append("</version>\n") }
            append("    </versions>\n")
            lastUpdated?.let { append("    <lastUpdated>").append(formatTimestamp(it)).append("</lastUpdated>\n") }
            append("  </versioning>\n")
            append("</metadata>\n")
        }
    }

    /**
     * Version-level metadata of a snapshot (`1.0-SNAPSHOT/maven-metadata.xml`): which
     * timestamped file each classifier/extension resolves to. The build stamp comes from the
     * file names maven-publish wrote (`lib-1.0-20240102.030405-7.jar`).
     */
    public fun snapshotMetadata(
        coordinate: Coordinate,
        manifest: Manifest,
        publishedAt: Long,
    ): String {
        val files =
            manifest
                .filesOf(coordinate.artifact)
                .keys
                .mapNotNull { MavenPath.parseFile(coordinate, it) }
                .filter { it.snapshotStamp != null }
        val stamp =
            files.mapNotNull { it.snapshotStamp }.maxWithOrNull(
                compareBy({ it.substringBefore('-') }, {
                    it.substringAfter('-').toIntOrNull()
                        ?: 0
                }),
            )
        val base = coordinate.version.removeSuffix("-SNAPSHOT")
        val updated = formatTimestamp(publishedAt)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<metadata modelVersion=\"1.1.0\">\n")
            append("  <groupId>").append(xml(coordinate.group)).append("</groupId>\n")
            append("  <artifactId>").append(xml(coordinate.artifact)).append("</artifactId>\n")
            append("  <version>").append(xml(coordinate.version)).append("</version>\n")
            append("  <versioning>\n")
            if (stamp != null) {
                append("    <snapshot>\n")
                append("      <timestamp>").append(stamp.substringBefore('-')).append("</timestamp>\n")
                append("      <buildNumber>").append(stamp.substringAfter('-')).append("</buildNumber>\n")
                append("    </snapshot>\n")
            } else {
                append("    <snapshot>\n")
                append("      <localCopy>true</localCopy>\n")
                append("    </snapshot>\n")
            }
            append("    <lastUpdated>").append(updated).append("</lastUpdated>\n")
            val current = files.filter { it.snapshotStamp == stamp }.sortedWith(compareBy({ it.extension }, { it.classifier ?: "" }))
            if (current.isNotEmpty()) {
                append("    <snapshotVersions>\n")
                current.forEach { file ->
                    append("      <snapshotVersion>\n")
                    file.classifier?.let { append("        <classifier>").append(xml(it)).append("</classifier>\n") }
                    append("        <extension>").append(xml(file.extension)).append("</extension>\n")
                    append("        <value>").append(xml("$base-${file.snapshotStamp}")).append("</value>\n")
                    append("        <updated>").append(updated).append("</updated>\n")
                    append("      </snapshotVersion>\n")
                }
                append("    </snapshotVersions>\n")
            }
            append("  </versioning>\n")
            append("</metadata>\n")
        }
    }

    /** `20240102.030405` for a Unix time, the form snapshot stamps use. */
    public fun snapshotTimestamp(unixSeconds: Long): String = SNAPSHOT_TIMESTAMP.format(Instant.ofEpochSecond(unixSeconds))

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
