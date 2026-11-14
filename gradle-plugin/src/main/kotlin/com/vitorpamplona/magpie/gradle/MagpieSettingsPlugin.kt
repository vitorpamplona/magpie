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
package com.vitorpamplona.magpie.gradle

import com.vitorpamplona.magpie.bridge.EngineConfig
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import java.io.File

/**
 * `id("com.vitorpamplona.magpie")` in `settings.gradle.kts`: resolves the configured groups from
 * Nostr + Blossom through a local Maven repository, and only from there (`exclusiveContent`), so
 * those groups can never be served by another repository.
 *
 * Nothing here touches the network: the repository's URL is a provider, and the server behind it
 * starts when Gradle first asks for a file. Under `--offline`, Gradle won't call any HTTP
 * repository, so the groups resolve from the plain Maven mirror of everything Magpie served
 * before (`~/.gradle/magpie/mirror/<publisher>/`).
 */
class MagpieSettingsPlugin : Plugin<Settings> {
    private val logger = Logging.getLogger(MagpieSettingsPlugin::class.java)

    override fun apply(settings: Settings) {
        val ext = settings.extensions.create("magpie", MagpieExtension::class.java)
        val providers = settings.providers
        ext.lockFile.convention(settings.layout.rootDirectory.file("gradle/magpie.lock"))
        ext.useLockFile.convention(true)
        ext.frozenLock.convention(providers.gradleProperty("magpie.frozenLock").map { it.toBoolean() }.orElse(false))
        ext.cacheDir.convention(
            settings.layout.rootDirectory.dir(
                settings.gradle.gradleUserHomeDir
                    .resolve("magpie")
                    .absolutePath,
            ),
        )
        ext.relayTimeoutMillis.convention(10_000L)

        settings.gradle.settingsEvaluated { configure(settings, ext) }
    }

    private fun configure(
        settings: Settings,
        ext: MagpieExtension,
    ) {
        val groups = ext.groups.toList()
        if (groups.isEmpty()) {
            logger.warn("Magpie: no groups configured; add magpie { group(\"com.example\") { publisher(\"npub1…\") } }")
            return
        }
        val offline = settings.startParameter.isOffline
        if (ext.indexRelays.get().isEmpty()) {
            // Without a domain there's no nostr.json to name the key's relays, so only an index relay can.
            groups.filter { it.pin != MagpieGroup.DOMAIN && !it.pin.startsWith("nip05:") }.takeIf { it.isNotEmpty() }?.let { pinned ->
                throw GradleException(
                    "Magpie: ${pinned.joinToString { it.group }} trust${if (pinned.size == 1) "s" else ""} a key directly, and Magpie finds that key's " +
                        "relays through its relay list (kind 10002) on index relays. Add magpie { indexRelays(\"wss://purplepag.es\") } (any relays that index relay lists).",
                )
            }
        }
        val cacheRoot = ext.cacheDir.get().asFile
        val lockFile = if (ext.useLockFile.get()) ext.lockFile.get().asFile else null

        val config =
            buildMap {
                put(EngineConfig.INDEX_RELAYS, ext.indexRelays.get().joinToString("\n"))
                put(EngineConfig.GROUPS, groups.joinToString("\n") { "${it.group}=${it.pin}" })
                put(EngineConfig.FALLBACK_SERVERS, ext.fallbackServers.get().joinToString("\n"))
                put(EngineConfig.CACHE_DIR, cacheRoot.absolutePath)
                put(EngineConfig.LOCK_FILE, lockFile?.absolutePath ?: "")
                put(EngineConfig.LOCK_FROZEN, ext.frozenLock.get().toString())
                put(EngineConfig.OFFLINE, offline.toString())
                put(EngineConfig.RELAY_TIMEOUT_MS, ext.relayTimeoutMillis.get().toString())
                ext.nip05BaseUrl.orNull?.let { put(EngineConfig.NIP05_BASE_URL, it) }
            }

        val service =
            settings.gradle.sharedServices.registerIfAbsent("magpieResolver", MagpieResolverService::class.java) {
                parameters.config.putAll(config)
                parameters.cacheRoot.set(cacheRoot)
            }

        val register: (RepositoryHandler) -> Unit =
            if (offline) {
                { repos -> registerOffline(repos, groups, cacheRoot, lockFile) }
            } else {
                { repos -> registerOnline(repos, groups, service.map { it.url }) }
            }

        register(settings.dependencyResolutionManagement.repositories)

        // Under PREFER_PROJECT (Gradle's default), a project that declares its own repositories
        // ignores the settings' ones: give it the Magpie repository too.
        val mode = settings.dependencyResolutionManagement.repositoriesMode.get()
        if (mode == RepositoriesMode.PREFER_PROJECT) {
            settings.gradle.beforeProject {
                val project = this
                project.afterEvaluate {
                    val repos = project.repositories
                    if (repos.isNotEmpty() && repos.none { it.name.startsWith(REPO_NAME) }) register(repos)
                }
            }
        }
    }

    private fun registerOnline(
        repos: RepositoryHandler,
        groups: List<MagpieGroup>,
        url: Provider<String>,
    ) {
        repos.exclusiveContent {
            forRepositories(
                repos.maven {
                    name = REPO_NAME
                    // Resolved when Gradle first needs it: that is what starts the local server.
                    setUrl(url)
                    isAllowInsecureProtocol = true
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                    }
                },
            )
            filter { groups.forEach { includeGroupAndSubgroups(it.group) } }
        }
    }

    private fun registerOffline(
        repos: RepositoryHandler,
        groups: List<MagpieGroup>,
        cacheRoot: File,
        lockFile: File?,
    ) {
        val lockedKeys = lockFile?.takeIf { it.isFile }?.let { readLockedPublishers(it) }.orEmpty()

        fun keyOf(group: MagpieGroup): String? {
            val pin = group.pin
            return if (pin == MagpieGroup.DOMAIN || pin.startsWith("nip05:")) lockedKeys[group.group] else Bech32.publicKeyHex(pin)
        }
        val keyed = groups.associateWith { keyOf(it) }
        keyed.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let { missing ->
            logger.warn("Magpie (--offline): no key for ${missing.joinToString { it.group }} in magpie.lock yet; resolve once online first")
        }
        keyed.entries
            .filter { it.value != null }
            .groupBy({ it.value!! }, { it.key })
            .forEach { (pubKey, sameKey) ->
                repos.exclusiveContent {
                    forRepositories(
                        repos.maven {
                            name = "$REPO_NAME-offline-${pubKey.take(8)}"
                            setUrl(File(cacheRoot, "mirror/$pubKey").toURI())
                            metadataSources {
                                gradleMetadata()
                                mavenPom()
                            }
                        },
                    )
                    filter {
                        sameKey.forEach { group ->
                            // The group and its subgroups, except subgroups another key owns: two
                            // exclusive filters claiming one module would hide it from both.
                            val others = groups.filter { it.group.startsWith(group.group + ".") && keyed[it] != pubKey }
                            includeGroupByRegex(groupRegex(group.group, others.map { it.group }))
                        }
                    }
                }
            }
    }

    private fun readLockedPublishers(lock: File): Map<String, String> =
        lock
            .readLines()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 3 && it[0] == "publisher" }
            .associate { it[1] to it[2] }

    internal companion object {
        const val REPO_NAME = "magpie"
    }
}

/** [group] and its subgroups, minus [excluded] subgroups and theirs. */
internal fun groupRegex(
    group: String,
    excluded: List<String>,
): String {
    val q = Regex.escape(group)
    if (excluded.isEmpty()) return "^$q(\\..*)?$"
    val except = excluded.joinToString("|") { Regex.escape(it.removePrefix(group)) }
    return "^$q(?!(?:$except)(?:\\.|$))(\\..*)?$"
}

/** Repositories this plugin added, for tests and diagnostics. */
internal fun MavenArtifactRepository.isMagpie(): Boolean = name.startsWith(MagpieSettingsPlugin.REPO_NAME)

/** Just enough bech32 to turn an npub into hex at configuration time (no engine needed). */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun publicKeyHex(value: String): String? {
        val v = value.trim().lowercase()
        if (v.length == 64 && v.all { it in '0'..'9' || it in 'a'..'f' }) return v
        if (!v.startsWith("npub1")) return null
        val data = v.substring(5).map { CHARSET.indexOf(it) }
        if (data.any { it < 0 } || data.size < 6) return null
        val payload = data.dropLast(6)
        var acc = 0
        var bits = 0
        val out = mutableListOf<Int>()
        for (value5 in payload) {
            acc = (acc shl 5) or value5
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out += (acc shr bits) and 0xff
            }
        }
        if (out.size != 32) return null
        return out.joinToString("") { "%02x".format(it) }
    }
}
