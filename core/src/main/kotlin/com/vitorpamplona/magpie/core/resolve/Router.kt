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
import com.vitorpamplona.magpie.spec.ChecksumAlgorithm
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.MavenPath
import com.vitorpamplona.magpie.spec.toPath
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import java.io.File
import java.net.URI
import java.nio.file.Files

/** What the router answers; transports (JDK HttpServer, Ktor) turn it into HTTP. */
public sealed interface RouterResponse {
    public val status: Int
    public val headers: Map<String, String>

    public data class FileBody(
        val file: File,
        val contentType: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : RouterResponse {
        override val status: Int get() = 200
    }

    public data class Bytes(
        val bytes: ByteArray,
        val contentType: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : RouterResponse {
        override val status: Int get() = 200

        override fun equals(other: Any?): Boolean = other is Bytes && other.bytes.contentEquals(bytes) && other.contentType == contentType

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A HEAD answer: the length and headers of a file, without fetching it. */
    public data class Head(
        val length: Long,
        val contentType: String,
        override val headers: Map<String, String> = emptyMap(),
    ) : RouterResponse {
        override val status: Int get() = 200
    }

    public data class Error(
        override val status: Int,
        val message: String,
    ) : RouterResponse {
        override val headers: Map<String, String> get() = emptyMap()
    }
}

/**
 * Maps a Maven repository path to bytes: `maven-metadata.xml` generated from events, artifact
 * files from manifests and Blossom, and every checksum sidecar computed locally. The local
 * resolver serves through this.
 */
public class Router(
    private val resolver: Resolver,
    private val checksums: ChecksumFiles,
    private val log: MagpieLog,
    /** Writes what was served into a Maven layout for `--offline` builds; null to skip. */
    private val mirror: RepositoryMirror? = null,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val notFound = ExpiringCache<String, Unit>(10_000, clock)

    /**
     * [rawPath] as the transport received it (percent-encoded). With [head], files are answered
     * from the manifest (length, type) without downloading them.
     */
    public suspend fun handle(
        rawPath: String,
        head: Boolean = false,
    ): RouterResponse {
        val path = decode(rawPath.substringBefore('?')).trimStart('/')
        if (notFound.contains(path)) return RouterResponse.Error(404, "Not found (cached miss)")
        val response =
            try {
                route(path, head)
            } catch (e: MagpieSecurityException) {
                log.error("Magpie refused $path: ${e.message}")
                RouterResponse.Error(500, e.message ?: "refused")
            } catch (e: BlobNotFoundException) {
                log.warn("Magpie could not fetch $path: ${e.message}")
                RouterResponse.Error(502, e.message ?: "blob unavailable")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Magpie failed on $path: ${e.message}")
                RouterResponse.Error(502, e.message ?: e.javaClass.simpleName)
            }
        if (response is RouterResponse.Error && response.status == 404) notFound.put(path, Unit, resolver.settings.negativeTtlMs)
        return response
    }

    /** Percent-decodes [raw] (`%2B` -> `+`), leaving `+` itself alone; garbage decodes to nothing. */
    private fun decode(raw: String): String = runCatching { URI("http://h/" + raw.trimStart('/')).path }.getOrNull() ?: ""

    private suspend fun route(
        path: String,
        head: Boolean,
    ): RouterResponse {
        val parsed = MavenPath.parse(path) ?: return RouterResponse.Error(404, "Not a Maven path")
        val group =
            when (parsed) {
                is MavenPath.ArtifactMetadata -> parsed.module.group
                is MavenPath.VersionMetadata -> parsed.coordinate.group
                is MavenPath.ArtifactFile -> parsed.coordinate.group
            }
        val publisher = resolver.publisherFor(group) ?: return RouterResponse.Error(404, "No publisher is trusted for $group")
        return when (parsed) {
            is MavenPath.ArtifactMetadata -> artifactMetadata(parsed, publisher.pubKey)
            is MavenPath.VersionMetadata -> versionMetadata(parsed, publisher.pubKey)
            is MavenPath.ArtifactFile -> file(parsed, publisher.pubKey, head)
        }
    }

    private suspend fun artifactMetadata(
        path: MavenPath.ArtifactMetadata,
        publisher: HexKey,
    ): RouterResponse {
        val versions = resolver.versions(path.module, publisher)
        if (versions.isEmpty()) return RouterResponse.Error(404, "No versions of ${path.module}")
        val xml = MetadataGenerator.artifactMetadata(path.module, versions).toByteArray()
        return bytes(path, xml, "application/xml", publisher)
    }

    private suspend fun versionMetadata(
        path: MavenPath.VersionMetadata,
        publisher: HexKey,
    ): RouterResponse {
        val snapshot = resolver.snapshot(path.coordinate, publisher) ?: return RouterResponse.Error(404, "No snapshot ${path.coordinate}")
        val manifest = resolver.manifest(snapshot)
        val xml = MetadataGenerator.snapshotMetadata(path.coordinate, manifest, snapshot.createdAt).toByteArray()
        return bytes(path, xml, "application/xml", publisher)
    }

    private suspend fun file(
        path: MavenPath.ArtifactFile,
        publisher: HexKey,
        head: Boolean,
    ): RouterResponse {
        val coordinate = path.coordinate
        val publication =
            if (coordinate.isSnapshot) {
                resolver.snapshot(coordinate, publisher)
            } else {
                resolver.release(coordinate, publisher)
            } ?: return RouterResponse.Error(404, "No release of $coordinate")

        val manifest = resolver.manifest(publication)
        val key = resolveKey(manifest, path) ?: return RouterResponse.Error(404, "${path.fileName} is not part of $coordinate")
        val entry = manifest[key]!!
        // A release's files, and a snapshot's timestamped ones, never change; a floating
        // -SNAPSHOT name points at whatever build is newest.
        val immutable = !coordinate.isSnapshot || key == path.manifestKey
        val headers =
            mapOf(
                "X-Magpie-Event" to publication.id,
                "X-Checksum-Sha256" to entry.sha256,
                "Cache-Control" to if (immutable) "public, max-age=31536000, immutable" else "no-cache",
            )

        val checksum = path.checksum
        if (head && checksum == null) return RouterResponse.Head(entry.size, contentTypeOf(path.fileName), headers)
        val storedPath = MavenPath.parseFile(coordinate, key.substringAfterLast('/'))!!.toPath()
        if (checksum == ChecksumAlgorithm.SHA256 && (mirror == null || mirror.has(publisher, storedPath))) {
            // Known from the manifest: no need for the blob.
            return RouterResponse.Bytes(entry.sha256.toByteArray(), "text/plain", headers)
        }
        val blob = resolver.blob(entry.sha256, entry.size, publication)
        // Mirror on checksum requests too: Gradle often fetches only a .sha1 and reuses a file it
        // already has, and the offline mirror must still end up with the module.
        mirror?.let {
            mirrorModuleMetadata(it, publisher, manifest, publication, coordinate)
            it.linkFile(publisher, storedPath, blob)
            if (key != path.manifestKey) it.linkFile(publisher, path.copy(checksum = null).toPath(), blob)
        }
        if (checksum != null) {
            val value = checksums.ofBlob(entry.sha256, blob, checksum)
            return RouterResponse.Bytes(value.toByteArray(), "text/plain", headers)
        }
        return RouterResponse.FileBody(blob, contentTypeOf(path.fileName), headers)
    }

    /**
     * An offline build reads the mirror through whichever metadata Gradle asks for, so whenever a
     * file of a module is served, its `.module` and `.pom` go to the mirror too.
     */
    private suspend fun mirrorModuleMetadata(
        mirror: RepositoryMirror,
        publisher: HexKey,
        manifest: Manifest,
        publication: com.vitorpamplona.magpie.spec.MagpiePublicationEvent,
        coordinate: com.vitorpamplona.magpie.spec.Coordinate,
    ) {
        manifest.filesOf(coordinate.artifact).forEach { (fileName, entry) ->
            if (!(fileName.endsWith(".pom") || fileName.endsWith(".module"))) return@forEach
            val path = MavenPath.parseFile(coordinate, fileName) ?: return@forEach
            try {
                mirror.linkFile(publisher, path.toPath(), resolver.blob(entry.sha256, entry.size, publication))
            } catch (e: Exception) {
                log.debug("Could not mirror $fileName: ${e.message}")
            }
        }
    }

    /** The manifest key for a request; a plain `-SNAPSHOT` name maps to the newest timestamped file. */
    private fun resolveKey(
        manifest: Manifest,
        path: MavenPath.ArtifactFile,
    ): String? {
        if (manifest[path.manifestKey] != null) return path.manifestKey
        if (!path.coordinate.isSnapshot || path.snapshotStamp != null) return null
        return manifest
            .filesOf(path.coordinate.artifact)
            .keys
            .mapNotNull { MavenPath.parseFile(path.coordinate, it) }
            .filter { it.snapshotStamp != null && it.classifier == path.classifier && it.extension == path.extension }
            .maxWithOrNull(compareBy(MavenPath.SNAPSHOT_STAMP_ORDER) { it.snapshotStamp!! })
            ?.manifestKey
    }

    private fun bytes(
        path: MavenPath,
        body: ByteArray,
        contentType: String,
        publisher: HexKey,
    ): RouterResponse {
        val checksum = path.checksum
        val headers = mapOf("Cache-Control" to "public, max-age=30")
        if (checksum != null) return RouterResponse.Bytes(checksums.ofBytes(body, checksum).toByteArray(), "text/plain", headers)
        mirror?.writeFile(publisher, path.toPath(), body)
        return RouterResponse.Bytes(body, contentType, headers)
    }

    public companion object {
        public fun contentTypeOf(fileName: String): String =
            when {
                ChecksumAlgorithm.fromFileName(fileName) != null -> "text/plain"
                fileName.endsWith(".pom") || fileName.endsWith(".xml") -> "application/xml"
                fileName.endsWith(".module") || fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".jar") || fileName.endsWith(".aar") -> "application/java-archive"
                fileName.endsWith(".asc") -> "application/pgp-signature"
                else -> "application/octet-stream"
            }
    }
}

/**
 * A plain Maven directory (`<dir>/<publisher>/<group path>/…`) of everything served, so an
 * `--offline` build can point Gradle at a `file:` repository. Gradle never sends requests to an
 * HTTP repository offline, not even to localhost. One tree per publisher keeps groups that trust
 * different keys apart.
 */
public class RepositoryMirror(
    public val dir: File,
) {
    public fun root(publisher: HexKey): File = File(dir, publisher)

    public fun has(
        publisher: HexKey,
        relativePath: String,
    ): Boolean = File(root(publisher), relativePath).isFile

    public fun linkFile(
        publisher: HexKey,
        relativePath: String,
        blob: File,
    ) {
        val target = File(root(publisher), relativePath)
        if (target.isFile && target.length() == blob.length()) return
        try {
            Files2.ensureDir(target.parentFile)
            val tmp = File(target.parentFile, target.name + ".tmp-" + System.nanoTime())
            try {
                Files.createLink(tmp.toPath(), blob.toPath())
            } catch (e: Exception) {
                blob.copyTo(tmp, overwrite = true)
            }
            Files2.moveAtomically(tmp, target)
        } catch (e: Exception) {
            // The mirror is a convenience for offline builds; serving must not fail over it.
        }
    }

    public fun writeFile(
        publisher: HexKey,
        relativePath: String,
        bytes: ByteArray,
    ) {
        try {
            Files2.writeAtomically(File(root(publisher), relativePath), bytes)
        } catch (e: Exception) {
            // See linkFile.
        }
    }
}
