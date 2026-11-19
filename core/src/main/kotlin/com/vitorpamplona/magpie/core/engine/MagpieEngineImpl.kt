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
package com.vitorpamplona.magpie.core.engine

import com.vitorpamplona.magpie.bridge.EngineConfig
import com.vitorpamplona.magpie.bridge.EngineLogger
import com.vitorpamplona.magpie.bridge.MagpieEngine
import com.vitorpamplona.magpie.bridge.ResolverHandle
import com.vitorpamplona.magpie.core.nostr.QuartzNostrIO
import com.vitorpamplona.magpie.core.publish.BlossomClient
import com.vitorpamplona.magpie.core.publish.BunkerSigner
import com.vitorpamplona.magpie.core.publish.ListSettings
import com.vitorpamplona.magpie.core.publish.MagpieSigner
import com.vitorpamplona.magpie.core.publish.PublishSettings
import com.vitorpamplona.magpie.core.publish.Publisher
import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.publish.SecretKeySigner
import com.vitorpamplona.magpie.core.resolve.LocalRepoServer
import com.vitorpamplona.magpie.core.resolve.Nip05ResolverFactory
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.core.util.QuartzLogs
import kotlinx.coroutines.runBlocking
import java.io.File

/** Adapts Gradle's logger, as the bridge hands it over. */
public class BridgeLog(
    private val logger: EngineLogger,
) : MagpieLog {
    override fun error(message: String): Unit = logger.error(message)

    override fun warn(message: String): Unit = logger.warn(message)

    override fun lifecycle(message: String): Unit = logger.lifecycle(message)

    override fun info(message: String): Unit = logger.info(message)

    override fun debug(message: String): Unit = logger.debug(message)
}

internal fun Map<String, String>.list(key: String): List<String> =
    this[key]
        .orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun Map<String, String>.flag(key: String): Boolean = this[key]?.toBoolean() == true

/**
 * The engine the Gradle plugin loads by name ([EngineConfig.ENGINE_CLASS]) in its own class
 * loader. Everything crosses as strings, so neither side shares a Kotlin stdlib with the other.
 */
public class MagpieEngineImpl : MagpieEngine {
    override fun startResolver(
        config: Map<String, String>,
        logger: EngineLogger,
    ): ResolverHandle {
        val log = BridgeLog(logger)
        QuartzLogs.routeTo(log)
        val stack = ResolverStack.create(config, log)
        val server = LocalRepoServer(stack.router, log, LocalRepoServer.preferredPortFor(stack.cacheDir.absolutePath), stack.configId)
        log.info(
            "Magpie repository at ${server.url} (${if (stack.offline) {
                "offline"
            } else {
                "index relays: " + config.list(EngineConfig.INDEX_RELAYS).joinToString().ifEmpty { "none; using the relays domains list" }
            }
            })",
        )
        return object : ResolverHandle {
            override fun url(): String = server.url

            override fun close() {
                server.close()
                stack.close()
            }
        }
    }

    override fun runTask(
        task: String,
        config: Map<String, String>,
        logger: EngineLogger,
    ): Map<String, String> {
        val log = BridgeLog(logger)
        QuartzLogs.routeTo(log)
        val http = Http.client()
        val nostr = QuartzNostrIO(http, log)
        try {
            val signer = signerFor(config, nostr, log)
            signer.use {
                val keyCheck =
                    when (val check = config[EngineConfig.RELEASE_KEY_CHECK]?.takeIf { it.isNotBlank() } ?: EngineConfig.RELEASE_KEY_DOMAIN) {
                        EngineConfig.RELEASE_KEY_OFF -> null
                        EngineConfig.RELEASE_KEY_DOMAIN -> ReleaseKeyCheck(Nip05ResolverFactory.create(http, config))
                        else -> ReleaseKeyCheck(Nip05ResolverFactory.create(http, config), identifier = check)
                    }
                val publisher = Publisher(nostr, BlossomClient(http, log), signer, http, log, keyCheck = keyCheck)
                return runBlocking {
                    when (task) {
                        EngineConfig.TASK_PUBLISH, EngineConfig.TASK_CLOSE -> {
                            val settings =
                                PublishSettings(
                                    repoDir = File(config.getValue(EngineConfig.REPO_DIR)),
                                    group = config.getValue(EngineConfig.GROUP),
                                    version = config.getValue(EngineConfig.VERSION),
                                    rootArtifact = config[EngineConfig.ARTIFACT]?.takeIf { it.isNotBlank() },
                                    indexRelays = config.list(EngineConfig.INDEX_RELAYS),
                                    partial = config.flag(EngineConfig.PARTIAL),
                                    partialDir = config[EngineConfig.PARTIAL_DIR]?.takeIf { it.isNotBlank() }?.let(::File),
                                    partialName = config[EngineConfig.PARTIAL_NAME]?.takeIf { it.isNotBlank() } ?: "partial",
                                    dryRun = config.flag(EngineConfig.DRY_RUN),
                                )
                            val result = if (task == EngineConfig.TASK_CLOSE) publisher.close(settings) else publisher.publish(settings)
                            buildMap {
                                put("coordinate", result.coordinate)
                                put("manifest", result.manifestSha256)
                                result.eventId?.let { put("event", it) }
                                put("uploaded", result.uploaded.toString())
                                put("signatures", result.signatures.toString())
                                put("relays", result.acceptedBy.joinToString("\n"))
                                result.partialManifest?.let { put("partialManifest", it.path) }
                            }
                        }

                        EngineConfig.TASK_CREATE_SERVER_LIST -> {
                            val event = publisher.createServerList(config.list(EngineConfig.SERVERS), listSettings(config))
                            mapOf("event" to event.id)
                        }

                        EngineConfig.TASK_CREATE_RELAY_LIST -> {
                            val event = publisher.createRelayList(config.list(EngineConfig.LIST_RELAYS), listSettings(config))
                            mapOf("event" to event.id)
                        }

                        else -> {
                            throw IllegalArgumentException("Unknown Magpie task '$task'")
                        }
                    }
                }
            }
        } finally {
            nostr.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun listSettings(config: Map<String, String>): ListSettings =
        ListSettings(
            group = config[EngineConfig.GROUP]?.takeIf { it.isNotBlank() },
            indexRelays = config.list(EngineConfig.INDEX_RELAYS),
        )

    private fun signerFor(
        config: Map<String, String>,
        nostr: QuartzNostrIO,
        log: MagpieLog,
    ): MagpieSigner {
        val bunker = config[EngineConfig.BUNKER_URI]?.trim()?.takeIf { it.isNotEmpty() }
        val nsec = config[EngineConfig.NSEC]?.takeIf { it.isNotBlank() }
        return when {
            bunker != null -> {
                val cacheDir = config[EngineConfig.CACHE_DIR]?.takeIf { it.isNotBlank() }?.let(::File)
                BunkerSigner(
                    bunker,
                    nostr,
                    log,
                    clientKeyFile = cacheDir?.let { BunkerSigner.clientKeyFile(it, bunker) },
                    group = config[EngineConfig.GROUP]?.takeIf { it.isNotBlank() },
                )
            }
            nsec != null -> SecretKeySigner(nsec)
            else -> throw IllegalStateException(
                "No signer configured. Set a NIP-46 bunker URI (magpie.signer.bunker, default env MAGPIE_BUNKER) " +
                    "or a secret key (magpie.signer.nsecEnv, default env MAGPIE_NSEC).",
            )
        }
    }
}
