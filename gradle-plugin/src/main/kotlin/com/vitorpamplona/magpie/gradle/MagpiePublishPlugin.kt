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
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Delete
import java.io.File
import javax.inject.Inject

/**
 * `magpie { … }` in a published project's `build.gradle.kts`:
 * ```
 * magpie {
 *     relays("wss://relay.example.com")          // where to read your kind 10063 / 10002 (or -Pmagpie.relays=…)
 *     archivalRelays("wss://archive.example.com") // published to besides your NIP-65 write relays
 *     signer { bunkerEnv.set("MAGPIE_BUNKER") }   // the default; or nsecEnv
 * }
 * ```
 */
abstract class MagpiePublishExtension
    @Inject
    constructor() {
        /** Relays to read the publisher's server list (10063) and relay list (10002) from. */
        abstract val relays: ListProperty<String>

        /** Relays that receive every release in addition to the publisher's NIP-65 write relays. */
        abstract val archivalRelays: ListProperty<String>

        /** The root artifactId; the Kotlin Multiplatform root module or the single publication by default. */
        abstract val rootArtifact: Property<String>

        /** Where partial manifests are written (`--partial`) and read (closeNostrRelease). */
        abstract val partialsDir: DirectoryProperty

        /**
         * Refuse to publish unless the group's domain vouches for the signing key, as consumers
         * require (default true): `releases@<domain>` down the group's domain chain.
         */
        abstract val verifyReleaseKey: Property<Boolean>

        /** A NIP-05 identity on the group's domains to check instead of `releases@` (`maven@example.com`). */
        abstract val releaseKeyNip05: Property<String>

        /** Testing hook: NIP-05 lookups go to this base URL (`{domain}` stands for the domain). */
        abstract val nip05BaseUrl: Property<String>

        val signer: MagpieSignerSpec = MagpieSignerSpec()

        fun relays(vararg urls: String) {
            relays.addAll(urls.toList())
        }

        fun archivalRelays(vararg urls: String) {
            archivalRelays.addAll(urls.toList())
        }

        fun signer(action: Action<MagpieSignerSpec>) {
            action.execute(signer)
        }
    }

/**
 * Which environment variables hold the signer. The NIP-46 bunker URI wins when both are set; the
 * secret itself is read only when the task runs, never stored in the configuration cache.
 */
class MagpieSignerSpec {
    var bunkerEnv: String = "MAGPIE_BUNKER"
    var nsecEnv: String = "MAGPIE_NSEC"
}

/**
 * `id("com.vitorpamplona.magpie.publish")`: publishes this project's Maven publications to Nostr +
 * Blossom. It reuses `maven-publish` to write a local repository (`build/magpie-repo`) and never
 * reimplements Gradle's publication logic.
 *
 * - `publishToNostr` (`--partial` on each host of a multi-host build)
 * - `closeNostrRelease` merges the partial manifests and signs once
 * - `createNostrServerList --server=https://…` publishes your Blossom server list (kind 10063)
 */
class MagpiePublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val ext = project.extensions.create("magpie", MagpiePublishExtension::class.java)
        ext.partialsDir.convention(project.layout.buildDirectory.dir("magpie/partials"))
        ext.verifyReleaseKey.convention(true)
        // Also settable as Gradle properties (comma-separated), so a build can apply the plugin
        // only for releases without naming its types in the build script.
        ext.relays.convention(
            project.providers
                .gradleProperty("magpie.relays")
                .map { list(it) }
                .orElse(emptyList()),
        )
        ext.archivalRelays.convention(
            project.providers
                .gradleProperty("magpie.archivalRelays")
                .map { list(it) }
                .orElse(emptyList()),
        )
        val repoDir = project.layout.buildDirectory.dir("magpie-repo")
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        publishing.repositories.maven {
            name = "magpieLocal"
            setUrl(repoDir)
        }

        val clean =
            project.tasks.register("cleanMagpieRepo", Delete::class.java) {
                description = "Empties build/magpie-repo so a publish only carries this build's files."
                delete(repoDir)
            }
        project.tasks.configureEach {
            if (name.startsWith("publish") && name.endsWith("ToMagpieLocalRepository")) dependsOn(clean)
        }

        val rootArtifact =
            ext.rootArtifact.orElse(
                project.provider {
                    val publications = publishing.publications.withType(MavenPublication::class.java)
                    (publications.findByName("kotlinMultiplatform") ?: publications.firstOrNull())?.artifactId ?: project.name
                },
            )
        val cacheRoot = File(project.gradle.gradleUserHomeDir, "magpie")
        val configure: (MagpieEngineTask) -> Unit = { task ->
            task.group = "publishing"
            task.cacheRoot.set(cacheRoot)
            task.relays.set(ext.relays)
            task.archivalRelays.set(ext.archivalRelays)
            task.bunkerEnv.set(project.provider { ext.signer.bunkerEnv })
            task.nsecEnv.set(project.provider { ext.signer.nsecEnv })
            task.releaseKeyCheck.set(
                ext.verifyReleaseKey.flatMap { verify ->
                    if (verify) ext.releaseKeyNip05.orElse(EngineConfig.RELEASE_KEY_DOMAIN) else project.provider { EngineConfig.RELEASE_KEY_OFF }
                },
            )
            task.nip05BaseUrl.set(ext.nip05BaseUrl)
        }

        project.tasks.register("publishToNostr", PublishToNostrTask::class.java) {
            configure(this)
            description = "Publishes this project's Maven publications to Blossom and signs a Nostr release event."
            dependsOn("publishAllPublicationsToMagpieLocalRepository")
            engineTask.set(EngineConfig.TASK_PUBLISH)
            this.repoDir.set(repoDir)
            groupId.set(project.provider { project.group.toString() })
            version.set(project.provider { project.version.toString() })
            artifactId.set(rootArtifact)
            partialsDir.set(ext.partialsDir)
            partialName.set(project.provider { System.getProperty("os.name").lowercase().replace(' ', '-') + "-" + System.getProperty("os.arch") })
        }
        project.tasks.register("closeNostrRelease", CloseNostrReleaseTask::class.java) {
            configure(this)
            description = "Merges the partial manifests of a multi-host publish, checks every .module reference, and signs the release."
            engineTask.set(EngineConfig.TASK_CLOSE)
            groupId.set(project.provider { project.group.toString() })
            version.set(project.provider { project.version.toString() })
            artifactId.set(rootArtifact)
            partialsDir.set(ext.partialsDir)
        }
        project.tasks.register("createNostrServerList", CreateServerListTask::class.java) {
            configure(this)
            description = "Publishes a Blossom server list (kind 10063) for the signer: --server=https://… (repeatable)."
            engineTask.set(EngineConfig.TASK_CREATE_SERVER_LIST)
        }
    }
}

/** A comma-separated Gradle property as a list. */
private fun list(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
