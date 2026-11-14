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

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.utils.jcs.JsonCanonicalization
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One file of a release: its Blossom hash and length. */
public data class ManifestFile(
    val sha256: String,
    val size: Long,
) {
    init {
        require(isSha256Hex(sha256)) { "Not a lowercase sha256 hex: '$sha256'" }
        require(size >= 0) { "Negative size" }
    }
}

/**
 * The file table of a release: every Maven file name, as `artifact/version/fileName`, mapped to
 * its blob. Serialized canonically (RFC 8785: sorted keys, no whitespace) so the same files always
 * hash to the same manifest blob.
 *
 * Keys carry the artifact and version because one Kotlin Multiplatform release spans several
 * Maven modules (`lib`, `lib-jvm`, `lib-linuxx64`, …) that share a version.
 */
public data class Manifest(
    val coordinate: Coordinate,
    val files: Map<String, ManifestFile>,
    val schema: Int = MagpieSpec.MANIFEST_SCHEMA,
) {
    init {
        files.keys.forEach { key ->
            val problem = keyProblem(key)
            require(problem == null) { "Invalid manifest entry '$key': $problem" }
        }
    }

    // Indexed once: the router asks for one artifact's files on every request.
    private val byArtifact: Map<String, Map<String, ManifestFile>> by lazy {
        files.entries.groupBy({ it.key.substringBefore('/') }, { it.key.substringAfterLast('/') to it.value }).mapValues { it.value.toMap() }
    }

    /** The Maven modules (artifactIds) of this release: the root first, then every other one with files, sorted. */
    val artifacts: List<String> by lazy { listOf(coordinate.artifact) + byArtifact.keys.filter { it != coordinate.artifact }.sorted() }

    public fun modules(): List<ModuleId> = artifacts.map { ModuleId(coordinate.group, it) }

    public operator fun get(key: String): ManifestFile? = files[key]

    public fun toCanonicalJson(): String =
        JsonCanonicalization.canonicalize(
            mapOf(
                "schema" to schema,
                "coordinate" to coordinate.toString(),
                "files" to files.mapValues { (_, f) -> mapOf("sha256" to f.sha256, "size" to f.size) },
            ),
        )

    public fun toBytes(): ByteArray = toCanonicalJson().encodeToByteArray()

    /** The manifest blob's sha256: its Blossom address. */
    public fun sha256Hex(): String = sha256(toBytes()).toHexKey()

    /** Every file of [artifact] in this release, by file name. */
    public fun filesOf(artifact: String): Map<String, ManifestFile> = byArtifact[artifact].orEmpty()

    private fun keyProblem(key: String): String? {
        val parts = key.split('/')
        if (parts.size != 3) return "expected artifact/version/fileName"
        if (!parts.all { isValidSegment(it) }) return "bad path segment"
        if (parts[1] != coordinate.version) return "version ${parts[1]} differs from the release's ${coordinate.version}"
        if (MavenPath.parseFile(Coordinate(coordinate.group, parts[0], parts[1]), parts[2]) == null) {
            return "file name does not belong to ${parts[0]}:${parts[1]}"
        }
        return null
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a manifest blob. Unknown fields are ignored; a newer [schema] is refused. */
        public fun parse(text: String): Manifest = parse(json.parseToJsonElement(text))

        /**
         * Parses a manifest blob, which must be canonical (RFC 8785) JSON: the bytes that were
         * hashed are exactly what every reader sees, with no duplicate keys or alternative
         * spellings for two readers to disagree about.
         */
        public fun parse(bytes: ByteArray): Manifest {
            val text = bytes.decodeToString()
            val element = json.parseToJsonElement(text)
            require(JsonCanonicalization.canonicalize(plain(element)) == text) { "Manifest is not canonical JSON (RFC 8785)" }
            return parse(element)
        }

        private fun parse(element: JsonElement): Manifest {
            val root = element as? JsonObject ?: throw IllegalArgumentException("Manifest is not an object")
            val schema = (root["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("Manifest has no numeric schema")
            require(schema >= 1) { "Manifest schema $schema is not valid" }
            require(schema <= MagpieSpec.MANIFEST_SCHEMA) { "Manifest schema $schema is newer than this Magpie understands" }
            val coordinate =
                Coordinate.parse(
                    (root["coordinate"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Manifest has no coordinate"),
                )
            val files = root["files"] as? JsonObject ?: throw IllegalArgumentException("Manifest has no files")
            return Manifest(
                coordinate = coordinate,
                files =
                    files.mapValues { (key, value) ->
                        val obj = value as? JsonObject ?: throw IllegalArgumentException("Entry $key is not an object")
                        ManifestFile(
                            sha256 = (obj["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Entry $key has no sha256"),
                            size = (obj["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("Entry $key has no numeric size"),
                        )
                    },
                schema = schema,
            )
        }

        /** A JSON tree as the plain values [JsonCanonicalization] takes. */
        private fun plain(element: JsonElement): Any? =
            when (element) {
                is JsonNull -> null
                is JsonObject -> element.mapValues { plain(it.value) }
                is JsonArray -> element.map { plain(it) }
                is JsonPrimitive ->
                    when {
                        element.isString -> element.content
                        element.booleanOrNull != null -> element.booleanOrNull
                        element.longOrNull != null -> element.longOrNull
                        else -> element.doubleOrNull
                    }
            }

        /**
         * Merges the partial manifests that several CI hosts produced for one release. The same
         * file uploaded by two hosts must have the same hash; anything else is a broken build.
         */
        public fun merge(partials: List<Manifest>): Manifest {
            require(partials.isNotEmpty()) { "Nothing to merge" }
            val coordinate = partials.first().coordinate
            partials.forEach {
                require(it.coordinate == coordinate) { "Partial manifests disagree on the coordinate: $coordinate vs ${it.coordinate}" }
            }
            val merged = mutableMapOf<String, ManifestFile>()
            partials.forEach { partial ->
                partial.files.forEach { (key, file) ->
                    val existing = merged[key]
                    require(existing == null || existing == file) {
                        "Hosts disagree on $key: ${existing?.sha256} vs ${file.sha256}"
                    }
                    merged[key] = file
                }
            }
            return Manifest(coordinate, merged)
        }
    }
}

public fun isSha256Hex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
