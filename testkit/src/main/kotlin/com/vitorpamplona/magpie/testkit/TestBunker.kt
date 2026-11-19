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
package com.vitorpamplona.magpie.testkit

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequest
import com.vitorpamplona.quartz.nip46RemoteSigner.BunkerRequestConnect
import com.vitorpamplona.quartz.nip46RemoteSigner.server.BunkerRequestProcessor
import com.vitorpamplona.quartz.nip46RemoteSigner.server.Nip46ConnectDecision
import com.vitorpamplona.quartz.nip46RemoteSigner.server.Nip46RequestAuthorizer
import com.vitorpamplona.quartz.nip46RemoteSigner.server.NostrConnectSignerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A NIP-46 bunker holding [identity]'s key, listening on [relay] through Quartz's
 * [NostrConnectSignerService]. Hand [uri] to a client as its `bunker://` address.
 */
class TestBunker(
    relay: InMemoryRelay,
    identity: TestIdentity,
    val secret: String = "magpie-test-secret",
) : AutoCloseable {
    private val transport = NostrSignerInternal(KeyPair())
    private val client = NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paired = ConcurrentHashMap.newKeySet<HexKey>()
    private val relays = setOf(RelayUrlNormalizer.normalize(relay.url))

    /** sign_event requests served. */
    val signRequests = AtomicInteger()

    /** connect requests accepted: one per pairing of a client. */
    val connects = AtomicInteger()

    /** Runs as a client's connect is accepted. */
    @Volatile
    var onConnect: () -> Unit = {}

    /** How long the person takes to approve the nth sign_event request (from 1). */
    @Volatile
    var approvalDelayMs: (n: Int) -> Long = { 0 }

    /** Approve one request at a time, like a person working through the queue. */
    @Volatile
    var oneApprovalAtATime: Boolean = false

    /** Refuse the nth sign_event request (from 1), as a person declining it. */
    @Volatile
    var decline: (n: Int) -> Boolean = { false }

    private val approving = Mutex()

    val uri: String = "bunker://${transport.pubKey}?relay=${relay.url}&secret=$secret"

    private val authorizer =
        object : Nip46RequestAuthorizer {
            override suspend fun isPaired(clientPubKey: HexKey): Boolean = clientPubKey in paired

            override suspend fun onConnect(
                clientPubKey: HexKey,
                request: BunkerRequestConnect,
            ): Nip46ConnectDecision =
                if (request.secret == secret) {
                    connects.incrementAndGet()
                    onConnect()
                    paired += clientPubKey
                    Nip46ConnectDecision.Accept(secret)
                } else {
                    Nip46ConnectDecision.Reject("wrong secret")
                }

            override suspend fun authorize(
                clientPubKey: HexKey,
                request: BunkerRequest,
            ): Boolean {
                if (request.method == "sign_event") {
                    val n = signRequests.incrementAndGet()
                    if (oneApprovalAtATime) approving.withLock { delay(approvalDelayMs(n)) } else delay(approvalDelayMs(n))
                    if (decline(n)) return false
                }
                return clientPubKey in paired
            }
        }

    private val service =
        NostrConnectSignerService(
            client = client,
            transportSigner = transport,
            processor = BunkerRequestProcessor(NostrSignerInternal(identity.keyPair), { relays }, authorizer),
            relays = relays,
        )

    init {
        scope.launch { service.run() }
        // NIP-46 requests are ephemeral: one a client sends before the bunker's REQ is in is
        // never delivered, and the client waits a whole request window to send it again.
        runBlocking {
            withTimeout(10_000) {
                while (synchronized(relay.received) { relay.received.none { it.startsWith("[\"REQ\"") && transport.pubKey in it } }) delay(10)
                delay(50)
            }
        }
    }

    override fun close() {
        scope.cancel()
        client.disconnect()
    }
}
