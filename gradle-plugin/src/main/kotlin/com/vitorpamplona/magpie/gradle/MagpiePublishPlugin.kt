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
 *     indexRelays("wss://purplepag.es")           // where your relay list (kind 10002) is looked up (or -Pmagpie.indexRelays=…)
 *     archivalRelays("wss://archive.example.com") // published to besides your own relays
 *     signer { bunkerEnv.set("MAGPIE_BUNKER") }   // the default; or nsecEnv
 * }
 * ```
 */
abstract class MagpiePublishExtension
    @Inject
    constructor() {
        /**
         * Index relays: where your relay list (kind 10002) is looked up, besides the relays your
         * domain's nostr.json lists for your key. Your server list and releases then live on your
         * own relays (write relays and the domain's); the index relays only when you have neither.
         */
        abstract val indexRelays: ListProperty<String>

        /** Relays that receive every release in addition to the publisher's own relays. */
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

        fun indexRelays(vararg urls: String) {
            indexRelays.addAll(urls.toList())
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
 * - `createNostrRelayList --relay=wss://…` publishes your relay list (kind 10002) to the index relays
 */
class MagpiePublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val ext = project.extensions.create("magpie", MagpiePublishExtension::class.java)
        ext.partialsDir.convention(project.layout.buildDirectory.dir("magpie/partials"))
        ext.verifyReleaseKey.convention(true)
        // Also settable as Gradle properties (comma-separated), so a build can apply the plugin
        // only for releases without naming its types in the build script.
        ext.indexRelays.convention(
            project.providers
                .gradleProperty("magpie.indexRelays")
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

        // The coordinates are the root publication's, which a build may set without touching the
        // project's group (Kotlin/Native names its klibs after the project's).
        val rootPublication = {
            val publications = publishing.publications.withType(MavenPublication::class.java)
            publications.findByName("kotlinMultiplatform") ?: publications.firstOrNull()
        }
        val rootArtifact = ext.rootArtifact.orElse(project.provider { rootPublication()?.artifactId ?: project.name })
        val rootGroup = project.provider { rootPublication()?.groupId ?: project.group.toString() }
        val rootVersion = project.provider { rootPublication()?.version ?: project.version.toString() }
        val cacheRoot = File(project.gradle.gradleUserHomeDir, "magpie")
        val configure: (MagpieEngineTask) -> Unit = { task ->
            task.group = "publishing"
            task.cacheRoot.set(cacheRoot)
            task.indexRelays.set(ext.indexRelays)
            task.groupId.set(rootGroup)
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
            version.set(rootVersion)
            artifactId.set(rootArtifact)
            partialsDir.set(ext.partialsDir)
            partialName.set(project.provider { System.getProperty("os.name").lowercase().replace(' ', '-') + "-" + System.getProperty("os.arch") })
        }
        project.tasks.register("closeNostrRelease", CloseNostrReleaseTask::class.java) {
            configure(this)
            description = "Merges the partial manifests of a multi-host publish, checks every .module reference, and signs the release."
            engineTask.set(EngineConfig.TASK_CLOSE)
            version.set(rootVersion)
            artifactId.set(rootArtifact)
            partialsDir.set(ext.partialsDir)
        }
        project.tasks.register("createNostrServerList", CreateServerListTask::class.java) {
            configure(this)
            description = "Publishes a Blossom server list (kind 10063) for the signer: --server=https://… (repeatable)."
            engineTask.set(EngineConfig.TASK_CREATE_SERVER_LIST)
        }
        project.tasks.register("createNostrRelayList", CreateRelayListTask::class.java) {
            configure(this)
            description = "Publishes your relay list (kind 10002) to the index relays: --relay=wss://… (repeatable)."
            engineTask.set(EngineConfig.TASK_CREATE_RELAY_LIST)
        }
    }
}

/** A comma-separated Gradle property as a list. */
private fun list(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
