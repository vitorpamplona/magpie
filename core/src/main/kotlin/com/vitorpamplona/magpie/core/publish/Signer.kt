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
import com.vitorpamplona.magpie.core.util.MagpieLog
import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.DynamicSubscription
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerClientMetadata
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestSign
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectEvent
import com.vitorpamplona.quartz.nip46RemoteSigner.NostrConnectURI
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.NostrSignerRemote
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.RemoteSignerManager
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.SignResponse
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.SignResult
import com.vitorpamplona.quartz.nip46RemoteSigner.signer.SignerResult
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Secp256k1Instance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Signs for a publisher, counting signatures (a release takes two: one Blossom upload
 * authorization and the release event).
 */
public interface MagpieSigner : AutoCloseable {
    /** The signing key; a remote signer connects (and asks its user to approve) to answer. */
    public suspend fun pubKey(): HexKey

    /** The signing key when it is known without reaching the signer, else null. */
    public suspend fun pubKeyIfKnown(): HexKey? = null

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

    override suspend fun pubKeyIfKnown(): HexKey = delegate.pubKey()

    override suspend fun sign(template: EventTemplate<*>): Event = delegate.sign(template)

    override val signatures: Int get() = delegate.signatures
}

/**
 * Signs through a NIP-46 bunker (`bunker://<remote>?relay=…&secret=…`): the key never leaves the
 * bunker, which is what CI should use. A local client key encrypts the conversation, and the
 * bunker knows the client by it: a client it already paired with reconnects without asking.
 *
 * When the URI carries a secret, the client key is [derivedClientKey] from it and [group], so
 * every task of a build, every later build and every CI runner (which starts with nothing) is the
 * same client for that group: one connection approval per group, ever, not one per release. A URI
 * without a secret has nothing private to derive from: the key is kept in [clientKeyFile] and
 * reused on this machine, or is throwaway without one.
 */
public class BunkerSigner(
    bunkerUri: String,
    nostr: NostrIO,
    private val log: MagpieLog,
    /**
     * How long the bunker may go without answering any request. A release sends all its
     * requests at once and a person approves them in a row, so a request only times out when
     * no request has been answered for this long, not this long after it was sent.
     */
    private val timeoutMs: Long = 150_000,
    clientKeyFile: File? = null,
    /** The group released: the client key is per group, and the bunker shows it as the app's name. */
    group: String? = null,
) : MagpieSigner {
    private val transport = NostrSignerInternal(derivedClientKey(bunkerUri, group) ?: clientKey(clientKeyFile, log))
    private val onAuthUrl: (String) -> Unit = { url -> log.lifecycle("The bunker asks you to approve this request: $url") }

    /** Connects and reads the key: Quartz's client, with its own request window. */
    private val remote =
        NostrSignerRemote.fromBunkerUri(
            bunkerUri = bunkerUri,
            signer = transport,
            client = nostr.client,
            clientMetadata = BunkerClientMetadata(name = group?.let { "Magpie: $it" } ?: "Magpie", url = MAGPIE_URL),
            onAuthUrl = onAuthUrl,
        )

    /**
     * Sends the sign requests. Quartz's [NostrSignerRemote] gives each request 65 s and one retry,
     * which a queue of approvals can outlast; this manager keeps resending the same request (the
     * same id, so the bunker doesn't queue it twice) until [sign]'s own window closes.
     */
    private val requests =
        RemoteSignerManager(
            timeout = ATTEMPT_MS,
            client = nostr.client,
            signer = transport,
            remoteKey = remote.remotePubkey,
            relayList = remote.relays,
            maxRetries = Int.MAX_VALUE,
            onAuthUrl = onAuthUrl,
        )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The bunker's answers to [requests]; [remote] listens for its own and drops these. */
    private val answers =
        DynamicSubscription(
            nostr.client,
            {
                remote.relays.associateWith {
                    listOf(Filter(kinds = listOf(NostrConnectEvent.KIND), tags = mapOf("p" to listOf(transport.pubKey))))
                }
            },
        ) { event ->
            if (event is NostrConnectEvent) {
                scope.launch {
                    try {
                        requests.newResponse(event)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.info("Ignoring a bunker message that doesn't decrypt or parse: ${e.message}")
                    }
                }
            }
        }

    /** When the bunker last answered any request; a request waits while answers keep coming. */
    private val lastAnswer = AtomicLong(0)

    @Volatile private var connected: HexKey? = null
    private val connecting = Mutex()
    private val count = AtomicInteger()

    override val signatures: Int get() = count.get()

    override suspend fun pubKeyIfKnown(): HexKey? = connected

    override suspend fun pubKey(): HexKey {
        connected?.let { return it }
        // Signatures may be asked for in parallel; only the first caller connects.
        return connecting.withLock {
            connected ?: withTimeout(timeoutMs) {
                remote.openSubscription()
                answers.refresh()
                remote.connect()
                remote.getPublicKey().also { connected = it }
            }
        }
    }

    override suspend fun sign(template: EventTemplate<*>): Event {
        pubKey()
        requests.relayList = remote.relays
        val sent = System.currentTimeMillis()
        val result =
            coroutineScope {
                val request =
                    async {
                        requests.launchWaitAndParse(
                            bunkerRequestBuilder = {
                                BunkerRequestSign(event = EventTemplate(template.createdAt, template.kind, template.tags, template.content))
                            },
                            parser = SignResponse::parse,
                        )
                    }
                var answer: SignerResult.RequestAddressed<SignResult>? = null
                while (answer == null) {
                    // Another request's answer moves the deadline: the bunker's user is working
                    // through the queue.
                    val quietUntil = maxOf(sent, lastAnswer.get()) + timeoutMs
                    answer = withTimeoutOrNull((quietUntil - System.currentTimeMillis()).coerceAtLeast(1)) { request.await() }
                    if (answer == null && System.currentTimeMillis() >= maxOf(sent, lastAnswer.get()) + timeoutMs) {
                        request.cancel()
                        throw IllegalStateException(
                            "The bunker answered no request for ${timeoutMs / 1000} s, so Magpie stopped waiting. Approve every " +
                                "request it shows, or check that it is online, then publish again.",
                        )
                    }
                }
                answer
            }
        lastAnswer.accumulateAndGet(System.currentTimeMillis(), ::maxOf)
        val event = (result as? SignerResult.RequestAddressed.Successful<SignResult>)?.result?.event ?: throw remote.convertExceptions("Could not sign", result)
        count.incrementAndGet()
        return MagpieEvents.wrap(event) ?: event
    }

    override fun close() {
        answers.close()
        scope.cancel()
        remote.closeSubscription()
    }

    public companion object {
        /** How long one send of a sign request waits before it is sent again (Quartz's window). */
        private const val ATTEMPT_MS = 65_000L

        private const val MAGPIE_URL = "https://github.com/vitorpamplona/magpie"

        /** Separates these bytes from any other use of the same secret: they are Magpie's NIP-46 client key. */
        private const val CLIENT_KEY_DOMAIN = "magpie/nip46-client-key/v1"

        /**
         * The client key for [bunkerUri]'s bunker and [group], or null when the URI has no secret
         * (or is not a `bunker://<hex pubkey>` URI). SHA-256 over the domain, the bunker's key, the
         * secret and the group, each length-prefixed; a digest that is not a valid secp256k1 secret
         * key (zero, or not below the curve order, about one in 2^128) is hashed again until it is,
         * so the result is always a usable nsec. The relays in the URI are not part of it: a
         * bunker that moves relays is still the same client.
         *
         * Anyone holding the URI can derive this key and be this client, as anyone holding it
         * could already pair a new one: keep the URI secret.
         */
        public fun derivedClientKey(
            bunkerUri: String,
            group: String?,
        ): KeyPair? {
            val bunker = NostrConnectURI.parseBunker(bunkerUri.trim()) ?: return null
            val secret = bunker.secret?.takeIf { it.isNotEmpty() } ?: return null
            val seed = ByteArrayOutputStream()
            for (part in listOf(CLIENT_KEY_DOMAIN, bunker.remoteSignerPubKey.lowercase(), secret, group.orEmpty())) {
                val bytes = part.encodeToByteArray()
                seed.write("${bytes.size}:".encodeToByteArray())
                seed.write(bytes)
            }
            var key = sha256(seed.toByteArray())
            while (!Secp256k1Instance.isPrivateKeyValid(key)) key = sha256(key)
            return KeyPair(key)
        }

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
         * The client key in [file], made when it is missing or unreadable. The key is written to
         * a temp file only its owner can read and linked into place, which fails if another task
         * got there first: racing tasks all end up on the key the file holds.
         */
        internal fun clientKey(
            file: File?,
            log: MagpieLog,
        ): KeyPair {
            if (file == null) return KeyPair()
            read(file)?.let { return it }
            Files2.ensureDir(file.parentFile)
            val fresh = KeyPair()
            val tmp = ownerOnlyTempFile(file.parentFile, log)
            try {
                tmp.writeText(fresh.privKey!!.toHexKey())
                // A file that holds no key (corrupt, truncated) is replaced.
                if (file.exists() && read(file) == null && !file.delete()) log.warn("Magpie: could not replace the unreadable $file")
                try {
                    Files.createLink(file.toPath(), tmp.toPath())
                } catch (e: FileAlreadyExistsException) {
                    // Another task made it first: use its key.
                } catch (e: UnsupportedOperationException) {
                    Files2.moveAtomically(tmp, file)
                } catch (e: IOException) {
                    // No hard links on this file system.
                    Files2.moveAtomically(tmp, file)
                }
            } finally {
                if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit()
            }
            return read(file) ?: fresh
        }

        private fun ownerOnlyTempFile(
            dir: File,
            log: MagpieLog,
        ): File =
            try {
                Files.createTempFile(dir.toPath(), "client", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toFile()
            } catch (e: UnsupportedOperationException) {
                // Not POSIX (Windows): restrict it before anything is written.
                File.createTempFile("client", ".tmp", dir).also { tmp ->
                    val ownerOnly =
                        tmp.setReadable(false, false) &&
                            tmp.setReadable(true, true) &&
                            tmp.setWritable(false, false) &&
                            tmp.setWritable(true, true)
                    if (!ownerOnly) log.warn("Magpie: could not make $tmp readable by its owner only")
                }
            }

        private fun read(file: File): KeyPair? {
            if (!file.isFile) return null
            val hex = file.readText().trim()
            if (hex.length != 64 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return runCatching { KeyPair(Hex.decode(hex)) }.getOrNull()
        }
    }
}
