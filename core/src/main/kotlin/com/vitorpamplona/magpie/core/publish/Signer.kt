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

import com.vitorpamplona.magpie.core.nostr.NostrIO
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
 * bunker, which is what CI should use. A throwaway local key encrypts the conversation.
 */
public class BunkerSigner(
    bunkerUri: String,
    nostr: NostrIO,
    private val log: MagpieLog,
    private val timeoutMs: Long = 120_000,
) : MagpieSigner {
    private val remote =
        NostrSignerRemote.fromBunkerUri(
            bunkerUri = bunkerUri,
            signer = NostrSignerInternal(KeyPair()),
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
}
