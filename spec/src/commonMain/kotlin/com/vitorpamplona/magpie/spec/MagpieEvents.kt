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
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate

/** The manifest blob an event pins. */
public data class ManifestPointer(
    val sha256: String,
    val size: Long,
)

/**
 * A release or a snapshot: an event that pins a manifest for a version of one or more modules.
 * Readers only; nothing here trusts the content before [com.vitorpamplona.magpie.spec.WinnerSelection]
 * and a signature check.
 */
public abstract class MagpiePublicationEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    kind: Int,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, kind, tags, content, sig) {
    // Tags are read once: selection calls these accessors for every event, many times.
    private val parsedModuleKeys: List<String> by lazy { tags.values(MagpieSpec.TAG_MODULE) }
    private val parsedModules: List<ModuleId> by lazy { parsedModuleKeys.mapNotNull { ModuleId.parseOrNull(it) } }
    private val parsedVersion: String? by lazy { tags.firstValue(MagpieSpec.TAG_VERSION) }
    private val parsedManifest: ManifestPointer? by lazy {
        val tag = tags.firstOrNull { it.size >= 2 && it[0] == MagpieSpec.TAG_MANIFEST }
        val sha = tag?.get(1)?.lowercase()
        if (tag == null || sha == null || !isSha256Hex(sha)) null else ManifestPointer(sha, tag.getOrNull(2)?.toLongOrNull() ?: -1)
    }

    /** Module keys (`group:artifact`) the event covers, root first. */
    public fun moduleKeys(): List<String> = parsedModuleKeys

    public fun modules(): List<ModuleId> = parsedModules

    public fun version(): String? = parsedVersion

    /** The root coordinate: the first module at [version]. */
    public fun coordinate(): Coordinate? {
        val module = parsedModules.firstOrNull() ?: return null
        val version = parsedVersion ?: return null
        return runCatching { module.withVersion(version) }.getOrNull()
    }

    public fun covers(
        module: ModuleId,
        version: String,
    ): Boolean = parsedVersion == version && module.key in parsedModuleKeys

    /** The manifest pointer; size -1 when the tag doesn't give a usable one. */
    public fun manifest(): ManifestPointer? = parsedManifest

    /** Every `x` hash: the manifest and the primary artifacts. */
    public fun hashes(): List<String> = tags.values(MagpieSpec.TAG_HASH)

    /** Blossom server base URLs the publisher uploaded to. */
    public fun servers(): List<String> = tags.values(MagpieSpec.TAG_SERVER)

    /** Structurally valid: has modules, a version, and a manifest with a plausible size. */
    public open fun isWellFormed(): Boolean =
        modules().isNotEmpty() &&
            version() != null &&
            manifest()?.size?.let { it in 0..MagpieSpec.MAX_MANIFEST_BYTES } == true &&
            coordinate() != null
}

/** A regular, immutable release. */
public class MagpieReleaseEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : MagpiePublicationEvent(id, pubKey, createdAt, KIND, tags, content, sig) {
    override fun isWellFormed(): Boolean = super.isWellFormed() && version()?.let { !isSnapshotVersion(it) } == true

    public companion object {
        public const val KIND: Int = MagpieSpec.KIND_RELEASE

        /**
         * The unsigned release of [manifest]. [primaryHashes] are the hashes of jars, aars, klibs,
         * POMs and Gradle module files, so a `#x` lookup finds who signed a file; they're dropped
         * from the end if the event would pass [MagpieSpec.MAX_EVENT_BYTES].
         */
        public fun build(
            manifest: Manifest,
            manifestSha256: String = manifest.sha256Hex(),
            manifestSize: Long = manifest.toBytes().size.toLong(),
            primaryHashes: List<String> = primaryHashesOf(manifest),
            servers: List<String> = emptyList(),
            createdAt: Long,
        ): EventTemplate<MagpieReleaseEvent> {
            require(!manifest.coordinate.isSnapshot) { "A release can't be a -SNAPSHOT; publish it as a snapshot" }
            return EventTemplate(
                createdAt,
                KIND,
                publicationTags(manifest, manifestSha256, manifestSize, primaryHashes, servers, dTag = null, label = "Release"),
                "",
            )
        }
    }
}

/** An addressable snapshot: the newest one for its `d` tag replaces older ones. */
public class MagpieSnapshotEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : MagpiePublicationEvent(id, pubKey, createdAt, KIND, tags, content, sig) {
    public fun dTag(): String? = tags.firstValue(MagpieSpec.TAG_D)

    override fun isWellFormed(): Boolean =
        super.isWellFormed() &&
            version()?.let { isSnapshotVersion(it) } == true &&
            dTag() == coordinate()?.toString()

    public companion object {
        public const val KIND: Int = MagpieSpec.KIND_SNAPSHOT

        public fun build(
            manifest: Manifest,
            manifestSha256: String = manifest.sha256Hex(),
            manifestSize: Long = manifest.toBytes().size.toLong(),
            primaryHashes: List<String> = primaryHashesOf(manifest),
            servers: List<String> = emptyList(),
            createdAt: Long,
        ): EventTemplate<MagpieSnapshotEvent> {
            require(manifest.coordinate.isSnapshot) { "A snapshot needs a -SNAPSHOT version" }
            return EventTemplate(
                createdAt,
                KIND,
                publicationTags(
                    manifest,
                    manifestSha256,
                    manifestSize,
                    primaryHashes,
                    servers,
                    dTag = manifest.coordinate.toString(),
                    label = "Snapshot",
                ),
                "",
            )
        }
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

private fun publicationTags(
    manifest: Manifest,
    manifestSha256: String,
    manifestSize: Long,
    primaryHashes: List<String>,
    servers: List<String>,
    dTag: String?,
    label: String,
): TagArray {
    require(isSha256Hex(manifestSha256)) { "Bad manifest hash" }
    val tags = mutableListOf<Array<String>>()
    dTag?.let { tags += arrayOf(MagpieSpec.TAG_D, it) }
    manifest.modules().forEach { tags += arrayOf(MagpieSpec.TAG_MODULE, it.key) }
    tags += arrayOf(MagpieSpec.TAG_VERSION, manifest.coordinate.version)
    tags += arrayOf(MagpieSpec.TAG_MANIFEST, manifestSha256, manifestSize.toString())
    tags += arrayOf(MagpieSpec.TAG_HASH, manifestSha256)
    servers.distinct().forEach { tags += arrayOf(MagpieSpec.TAG_SERVER, it) }
    tags += arrayOf(MagpieSpec.TAG_ALT, "$label of ${manifest.coordinate} (Maven artifacts on Nostr)")

    // Envelope: id, pubkey, sig, created_at, kind and punctuation, about 250 bytes.
    var size = 250 + tags.sumOf { tagJsonSize(it) }
    require(
        size <= MagpieSpec.MAX_EVENT_BYTES,
    ) { "Release event would exceed ${MagpieSpec.MAX_EVENT_BYTES} bytes before any artifact hash" }
    for (hash in primaryHashes) {
        if (hash == manifestSha256) continue
        val tag = arrayOf(MagpieSpec.TAG_HASH, hash)
        val extra = tagJsonSize(tag)
        if (size + extra > MagpieSpec.MAX_EVENT_BYTES) break
        tags += tag
        size += extra
    }
    return tags.toTypedArray()
}

private fun tagJsonSize(tag: Array<String>): Int = 3 + tag.sumOf { it.encodeToByteArray().size + 3 }

internal fun TagArray.values(name: String): List<String> = filter { it.size >= 2 && it[0] == name }.map { it[1] }

internal fun TagArray.firstValue(name: String): String? = firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

/** Wraps a generic event from a relay in its Magpie class, or null for other kinds. */
public object MagpieEvents {
    public fun wrap(event: Event): Event? =
        when (event.kind) {
            MagpieSpec.KIND_RELEASE ->
                event as? MagpieReleaseEvent
                    ?: MagpieReleaseEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            MagpieSpec.KIND_SNAPSHOT ->
                event as? MagpieSnapshotEvent
                    ?: MagpieSnapshotEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            else -> null
        }
}
