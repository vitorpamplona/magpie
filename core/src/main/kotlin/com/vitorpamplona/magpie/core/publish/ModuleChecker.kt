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

import com.vitorpamplona.magpie.spec.Coordinate
import com.vitorpamplona.magpie.spec.Manifest
import com.vitorpamplona.magpie.spec.MavenPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks that a release is complete: every file each Gradle `.module` names (variant files, and
 * the `.module` of every `available-at` target in the same group) is in the manifest. A
 * multi-host Kotlin Multiplatform release missing a host's klibs fails here, before signing.
 */
public object ModuleChecker {
    /** The manifest keys referenced but missing; empty when the release is complete. */
    public suspend fun missingReferences(
        manifest: Manifest,
        load: suspend (key: String) -> ByteArray,
    ): List<String> {
        val missing = sortedSetOf<String>()
        manifest.files.keys.filter { it.endsWith(".module") }.forEach { moduleKey ->
            val dir = moduleKey.substringBeforeLast('/')
            val json = Json.parseToJsonElement(load(moduleKey).decodeToString()) as? JsonObject ?: return@forEach
            val variants = json["variants"] as? JsonArray ?: return@forEach
            variants.forEach { variant ->
                val obj = variant as? JsonObject ?: return@forEach
                (obj["files"] as? JsonArray)?.forEach { file ->
                    val url = ((file as? JsonObject)?.get("url") as? JsonPrimitive)?.content ?: return@forEach
                    resolve(dir, url)?.let { if (!present(manifest, it)) missing += "$it (from $moduleKey)" }
                }
                val availableAt = ((obj["available-at"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content
                if (availableAt != null) {
                    resolve(dir, availableAt)?.let { if (!present(manifest, it)) missing += "$it (available-at in $moduleKey)" }
                }
            }
        }
        return missing.toList()
    }

    /**
     * Whether [key] is in the manifest. A snapshot's module metadata names files by their plain
     * `-SNAPSHOT` names while the repository holds timestamped ones; any build of the same
     * classifier and extension counts.
     */
    private fun present(
        manifest: Manifest,
        key: String,
    ): Boolean {
        if (manifest[key] != null) return true
        val (artifact, version, fileName) = key.split('/')
        val coordinate = runCatching { Coordinate(manifest.coordinate.group, artifact, version) }.getOrNull() ?: return false
        if (!coordinate.isSnapshot) return false
        val wanted = MavenPath.parseFile(coordinate, fileName) ?: return false
        return manifest.filesOf(artifact).keys.any { name ->
            val file = MavenPath.parseFile(coordinate, name)
            file != null && file.snapshotStamp != null && file.classifier == wanted.classifier && file.extension == wanted.extension
        }
    }

    /**
     * [url] relative to `artifact/version`, as a manifest key; null if it leaves the group (another
     * group's module is that group's release's business).
     */
    internal fun resolve(
        dir: String,
        url: String,
    ): String? {
        if (url.contains("://")) return null
        val parts = dir.split('/').toMutableList()
        url.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return if (parts.size == 3) parts.joinToString("/") else null
    }
}
