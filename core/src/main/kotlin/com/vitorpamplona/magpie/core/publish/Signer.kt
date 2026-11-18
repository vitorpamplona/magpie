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

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.magpie.core.nostr.NostrIO
import com.vitorpamplona.magpie.core.util.Files2
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.NostrSignerRemote
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signs for a publisher, counting signatures (a release takes two: one Blossom upload
 * authorization and the release event).
 */
public interface MagpieSigner : AutoCloseable {
    public suspend fun pubKey(): HexKey

    public suspend fun sign(template: EventTemplate<*>): Event

    /** How many events this signer has signed. */
    public val signatures: Int

    override fun close() {}
}

private abstract class CountingSigner : MagpieSigner {
    private val count = AtomicInteger()
    override val signatures: Int get() = count.get()

    abstract val quartz: NostrSigner

    override suspend fun sign(template: EventTemplate<*>): Event {
        val event = quartz.sign<Event>(template.createdAt, template.kind, template.tags, template.content)
        count.incrementAndGet()
        return MagpieEvents.wrap(event) ?: event
    }
}

/** Signs with a secret key from an environment variable (`nsec1…` or hex). */
public class SecretKeySigner(
    secret: String,
) : MagpieSigner {
    private val delegate =
        object : CountingSigner() {
            override val quartz =
                NostrSignerInternal(
                    KeyPair(
                        Hex.decode(
                            decodePrivateKeyAsHexOrNull(secret.trim())
                                ?: throw IllegalArgumentException("The signing key is not an nsec or hex secret key"),
                        ),
                    ),
                )

            override suspend fun pubKey(): HexKey = quartz.pubKey
        }

    override suspend fun pubKey(): HexKey = delegate.pubKey()

    override suspend fun sign(template: EventTemplate<*>): Event = delegate.sign(template)

    override val signatures: Int get() = delegate.signatures
}

/**
 * Signs through a NIP-46 bunker (`bunker://<remote>?relay=…&secret=…`): the key never leaves the
 * bunker, which is what CI should use. A local client key encrypts the conversation. With
 * [clientKeyFile] it is kept there and reused, so every task of a build (each in its own worker,
 * one per published project) and every later build on the machine is the client the bunker
 * already paired with: one connection approval, not one per task. Without it the key is
 * throwaway.
 */
public class BunkerSigner(
    bunkerUri: String,
    nostr: NostrIO,
    private val log: MagpieLog,
    // Longer than Quartz's own request window (65 s, a 2 s pause, one retry of 65 s).
    private val timeoutMs: Long = 150_000,
    clientKeyFile: File? = null,
) : MagpieSigner {
    private val remote =
        NostrSignerRemote.fromBunkerUri(
            bunkerUri = bunkerUri,
            signer = NostrSignerInternal(clientKey(clientKeyFile, log)),
            client = nostr.client,
            onAuthUrl = { url -> log.lifecycle("The bunker asks you to approve this request: $url") },
        )

    private var connected: HexKey? = null
    private val count = AtomicInteger()

    override val signatures: Int get() = count.get()

    override suspend fun pubKey(): HexKey {
        connected?.let { return it }
        return withTimeout(timeoutMs) {
            remote.openSubscription()
            remote.connect()
            remote.getPublicKey().also { connected = it }
        }
    }

    override suspend fun sign(template: EventTemplate<*>): Event {
        pubKey()
        val event =
            withTimeout(timeoutMs) {
                remote.sign<Event>(template.createdAt, template.kind, template.tags, template.content)
            }
        count.incrementAndGet()
        return MagpieEvents.wrap(event) ?: event
    }

    override fun close() {
        remote.closeSubscription()
    }

    public companion object {
        /** Where the client key for [bunkerUri]'s bunker lives under Magpie's [cacheDir]. */
        public fun clientKeyFile(
            cacheDir: File,
            bunkerUri: String,
        ): File {
            val remote = bunkerUri.substringAfter("://").substringBefore('?').lowercase()
            val name = if (remote.length == 64 && remote.all { it in '0'..'9' || it in 'a'..'f' }) remote else sha256(bunkerUri.encodeToByteArray()).toHexKey()
            return File(File(cacheDir, "bunker-clients"), "$name.key")
        }

        /**
         * The client key in [file], made (readable by the owner only) when it is missing or
         * unreadable. Tasks that race to make it end up on whichever key the file holds.
         */
        internal fun clientKey(
            file: File?,
            log: MagpieLog,
        ): KeyPair {
            if (file == null) return KeyPair()
            read(file)?.let { return it }
            val fresh = KeyPair()
            Files2.writeAtomically(file, fresh.privKey!!.toHexKey().toByteArray())
            val ownerOnly =
                file.setReadable(false, false) &&
                    file.setReadable(true, true) &&
                    file.setWritable(false, false) &&
                    file.setWritable(true, true)
            if (!ownerOnly) log.warn("Magpie: could not make $file readable by its owner only")
            return read(file) ?: fresh
        }

        private fun read(file: File): KeyPair? {
            if (!file.isFile) return null
            val hex = file.readText().trim()
            if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return runCatching { KeyPair(Hex.decode(hex)) }.getOrNull()
        }
    }
}
