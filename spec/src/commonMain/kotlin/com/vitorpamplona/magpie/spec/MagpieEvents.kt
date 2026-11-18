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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nipFCMavenRepositories.MavenModule
import com.vitorpamplona.quartz.nipFCMavenRepositories.MavenPublication
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot.MavenSnapshotEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag

/*
 * The event classes are Quartz's (nipFCMavenRepositories: MavenReleaseEvent, MavenSnapshotEvent,
 * ManifestTag). What stays here is Magpie's side of them: which artifact hashes a release lists and
 * the size budget that trims them, and the stricter checks a resolver applies before it uses an
 * event to build file paths.
 */

/** [module] as Quartz's NIP-FC type. */
public fun ModuleId.toMavenModule(): MavenModule = MavenModule(group, artifact)

/** Every module the event covers that is safe as a path, root first. */
public fun MavenPublication.moduleIds(): List<ModuleId> = moduleKeys().mapNotNull { ModuleId.parseOrNull(it) }

/** The root coordinate, when it is safe as a path. */
public fun MavenPublication.rootCoordinate(): Coordinate? = coordinate()?.let { Coordinate.parseOrNull(it.toString()) }

/** Whether this event is a candidate for [module] at [version]. */
public fun MavenPublication.covers(
    module: ModuleId,
    version: String,
): Boolean = covers(module.toMavenModule(), version)

/**
 * NIP-FC's [MavenPublication.isWellFormed] plus what Magpie needs before it trusts the event with
 * a path or a download: a root coordinate Maven accepts as a path, and a manifest no larger than
 * [MagpieSpec.MAX_MANIFEST_BYTES].
 */
public fun MavenPublication.isUsable(): Boolean {
    if (!isWellFormed()) return false
    val size = manifest()?.size ?: return false
    return size <= MagpieSpec.MAX_MANIFEST_BYTES && rootCoordinate() != null
}

/** Wraps a generic event from a relay in its NIP-FC class, or null for other kinds. */
public object MagpieEvents {
    public fun wrap(event: Event): Event? =
        when (event.kind) {
            MavenReleaseEvent.KIND ->
                event as? MavenReleaseEvent
                    ?: MavenReleaseEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            MavenSnapshotEvent.KIND ->
                event as? MavenSnapshotEvent
                    ?: MavenSnapshotEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            else -> null
        }

    /**
     * The unsigned release of [manifest]. The primary artifacts' hashes follow the manifest's
     * ([primaryHashesOf]), dropped from the end if the event would pass [MagpieSpec.MAX_EVENT_BYTES].
     */
    public fun release(
        manifest: Manifest,
        manifestSha256: String = manifest.sha256Hex(),
        manifestSize: Long = manifest.toBytes().size.toLong(),
        primaryHashes: List<String> = primaryHashesOf(manifest),
        servers: List<String> = emptyList(),
        createdAt: Long,
    ): EventTemplate<MavenReleaseEvent> {
        require(!manifest.coordinate.isSnapshot) { "A release can't be a -SNAPSHOT; publish it as a snapshot" }
        val pointer = ManifestTag(manifestSha256, manifestSize)
        val modules = manifest.modules().map { it.toMavenModule() }
        val version = manifest.coordinate.version
        val base = MavenReleaseEvent.build(modules, version, pointer, servers = servers, createdAt = createdAt)
        val hashes = withinBudget(base.tags, primaryHashes, manifestSha256)
        return MavenReleaseEvent.build(modules, version, pointer, hashes, servers, createdAt)
    }

    /** The unsigned snapshot of [manifest], with the same hash budget as [release]. */
    public fun snapshot(
        manifest: Manifest,
        manifestSha256: String = manifest.sha256Hex(),
        manifestSize: Long = manifest.toBytes().size.toLong(),
        primaryHashes: List<String> = primaryHashesOf(manifest),
        servers: List<String> = emptyList(),
        createdAt: Long,
    ): EventTemplate<MavenSnapshotEvent> {
        require(manifest.coordinate.isSnapshot) { "A snapshot needs a -SNAPSHOT version" }
        val pointer = ManifestTag(manifestSha256, manifestSize)
        val modules = manifest.modules().map { it.toMavenModule() }
        val version = manifest.coordinate.version
        val base = MavenSnapshotEvent.build(modules, version, pointer, servers = servers, createdAt = createdAt)
        val hashes = withinBudget(base.tags, primaryHashes, manifestSha256)
        return MavenSnapshotEvent.build(modules, version, pointer, hashes, servers, createdAt)
    }

    /** The prefix of [hashes] that fits in [MagpieSpec.MAX_EVENT_BYTES] beside [baseTags]. */
    private fun withinBudget(
        baseTags: TagArray,
        hashes: List<String>,
        manifestSha256: String,
    ): List<String> {
        // Envelope: id, pubkey, sig, created_at, kind and punctuation, about 250 bytes.
        var size = 250 + baseTags.sumOf { tagJsonSize(it) }
        require(size <= MagpieSpec.MAX_EVENT_BYTES) { "Release event would exceed ${MagpieSpec.MAX_EVENT_BYTES} bytes before any artifact hash" }
        val kept = mutableListOf<String>()
        for (hash in hashes.map { it.lowercase() }.distinct()) {
            if (hash == manifestSha256.lowercase()) continue
            val extra = tagJsonSize(arrayOf(MagpieSpec.TAG_HASH, hash))
            if (size + extra > MagpieSpec.MAX_EVENT_BYTES) break
            kept += hash
            size += extra
        }
        return kept
    }
}

/** The files a `#x` lookup should find: jars, aars, klibs, POMs and Gradle module metadata. */
public fun primaryHashesOf(manifest: Manifest): List<String> {
    val root = manifest.coordinate
    return manifest.files.entries
        .mapNotNull { (key, file) ->
            val parts = key.split('/')
            val path = MavenPath.parseFile(Coordinate(root.group, parts[0], parts[1]), parts[2]) ?: return@mapNotNull null
            if (!isPrimaryFile(path)) return@mapNotNull null
            // The root module first (if the size budget cuts, it cuts the platform modules), and
            // within a module: Gradle metadata, then the POM, then the artifact.
            val rank =
                when (path.extension) {
                    "module" -> 0
                    "pom" -> 1
                    else -> 2
                }
            Triple(if (parts[0] == root.artifact) 0 else 1, rank, key) to file.sha256
        }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
        .map { it.second }
        .distinct()
}

/** Primary files: `.module`, `.pom`, and the main jar/aar/klib (no classifier, or a cinterop one). */
public fun isPrimaryFile(file: MavenPath.ArtifactFile): Boolean =
    when (file.extension) {
        "module", "pom" -> file.classifier == null
        "jar", "aar", "klib" -> file.classifier == null || file.classifier.startsWith("cinterop-")
        else -> false
    }

private fun tagJsonSize(tag: Array<String>): Int = 3 + tag.sumOf { it.encodeToByteArray().size + 3 }
