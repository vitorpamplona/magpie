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
    /** Module keys (`group:artifact`) the event covers, root first. */
    public fun moduleKeys(): List<String> = tags.values(MagpieSpec.TAG_MODULE)

    public fun modules(): List<ModuleId> = moduleKeys().mapNotNull { ModuleId.parseOrNull(it) }

    public fun version(): String? = tags.firstValue(MagpieSpec.TAG_VERSION)

    /** The root coordinate: the first module at [version]. */
    public fun coordinate(): Coordinate? {
        val module = modules().firstOrNull() ?: return null
        val version = version() ?: return null
        return runCatching { module.withVersion(version) }.getOrNull()
    }

    public fun covers(
        module: ModuleId,
        version: String,
    ): Boolean = version() == version && module.key in moduleKeys()

    public fun manifest(): ManifestPointer? {
        val tag = tags.firstOrNull { it.size >= 2 && it[0] == MagpieSpec.TAG_MANIFEST } ?: return null
        val sha = tag[1].lowercase()
        if (!isSha256Hex(sha)) return null
        return ManifestPointer(sha, tag.getOrNull(2)?.toLongOrNull() ?: -1)
    }

    /** Every `x` hash: the manifest and the primary artifacts. */
    public fun hashes(): List<String> = tags.values(MagpieSpec.TAG_HASH)

    /** Blossom server base URLs the publisher uploaded to. */
    public fun servers(): List<String> = tags.values(MagpieSpec.TAG_SERVER)

    /** Structurally valid: has modules, a version, and a manifest. */
    public open fun isWellFormed(): Boolean = modules().isNotEmpty() && version() != null && manifest() != null && coordinate() != null
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

/** Marks a release as deprecated. Yanked releases still resolve. */
public class MagpieYankEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    /** The release ids this yank applies to. */
    public fun releaseIds(): List<String> = tags.values(MagpieSpec.TAG_EVENT)

    public fun moduleKeys(): List<String> = tags.values(MagpieSpec.TAG_MODULE)

    public fun version(): String? = tags.firstValue(MagpieSpec.TAG_VERSION)

    /** Why it was yanked (free text). */
    public fun reason(): String = content

    /** A yank only counts when the release's own author signed it. */
    public fun appliesTo(release: MagpieReleaseEvent): Boolean = release.id in releaseIds() && release.pubKey == pubKey

    public companion object {
        public const val KIND: Int = MagpieSpec.KIND_YANK

        public fun build(
            release: MagpieReleaseEvent,
            reason: String,
            createdAt: Long,
        ): EventTemplate<MagpieYankEvent> {
            val tags = mutableListOf<Array<String>>()
            tags += arrayOf(MagpieSpec.TAG_EVENT, release.id)
            tags += arrayOf(MagpieSpec.TAG_KIND, MagpieSpec.KIND_RELEASE.toString())
            release.moduleKeys().forEach { tags += arrayOf(MagpieSpec.TAG_MODULE, it) }
            release.version()?.let { tags += arrayOf(MagpieSpec.TAG_VERSION, it) }
            tags += arrayOf(MagpieSpec.TAG_ALT, "Yanked ${release.coordinate()}: $reason")
            return EventTemplate(createdAt, KIND, tags.toTypedArray(), reason)
        }
    }
}

/** The files a `#x` lookup should find: jars, aars, klibs, POMs and Gradle module metadata. */
public fun primaryHashesOf(manifest: Manifest): List<String> =
    manifest.files.entries
        .filter { (key, _) -> isPrimaryFile(key.substringAfterLast('/')) }
        .sortedBy { it.key }
        .map { it.value.sha256 }
        .distinct()

/** Primary files: the main jar/aar/klib (no classifier or a cinterop one), the POM and the `.module`. */
public fun isPrimaryFile(fileName: String): Boolean {
    if (fileName.endsWith(".asc")) return false
    if (fileName.endsWith(".pom") || fileName.endsWith(".module")) return true
    if (!(fileName.endsWith(".jar") || fileName.endsWith(".aar") || fileName.endsWith(".klib"))) return false
    return !(fileName.endsWith("-sources.jar") || fileName.endsWith("-javadoc.jar") || fileName.endsWith("-html-doc.jar"))
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
            MagpieSpec.KIND_YANK ->
                event as? MagpieYankEvent
                    ?: MagpieYankEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
            else -> null
        }
}
