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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.server.NostrServer
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * A NIP-01 relay on `ws://127.0.0.1:<port>/`, backed by Quartz's [NostrServer] over an in-memory
 * SQLite store: real websockets, real filter semantics (regular, replaceable and addressable
 * kinds), nothing on disk. Use [url] as a relay address.
 */
class InMemoryRelay : AutoCloseable {
    val store: IEventStore = EventStore(dbName = null)
    private val server = NostrServer(store)

    /** REQs received, for asserting what a client asked. */
    val reqCount = AtomicInteger()

    /** Every raw frame the relay received. */
    val received: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** When false, the relay accepts the socket and answers nothing (a dead relay). */
    @Volatile
    var responsive: Boolean = true

    private val engine: EmbeddedServer<*, *> =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/") {
                    val outgoing = Channel<String>(Channel.UNLIMITED)
                    val writer = launch { for (msg in outgoing) send(Frame.Text(msg)) }
                    try {
                        server.serve(send = { json -> outgoing.trySend(json) }) { session ->
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    received += text
                                    if (text.startsWith("[\"REQ\"")) reqCount.incrementAndGet()
                                    if (responsive) session.receive(text)
                                }
                            }
                        }
                    } finally {
                        outgoing.close()
                        writer.join()
                    }
                }
            }
        }.start(wait = false)

    val port: Int =
        runBlocking {
            engine.engine
                .resolvedConnectors()
                .first()
                .port
        }

    val url: String = "ws://127.0.0.1:$port/"

    /** Stores [event] directly, as if a client had published it. Throws if the relay refuses it. */
    fun add(vararg events: Event) {
        runBlocking {
            events.forEach { event ->
                val done = CompletableDeferred<IEventStore.InsertOutcome>()
                server.ingest(event) { done.complete(it) }
                val outcome = done.await()
                check(outcome !is IEventStore.InsertOutcome.Rejected) { "Relay refused ${event.id}: $outcome" }
            }
        }
    }

    fun query(filter: Filter): List<Event> = runBlocking { store.query<Event>(filter) }

    fun all(): List<Event> = query(Filter())

    override fun close() {
        engine.stop(0, 500)
        server.close()
        (store as? AutoCloseable)?.close()
    }
}
