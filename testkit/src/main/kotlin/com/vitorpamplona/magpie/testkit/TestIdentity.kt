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

import com.vitorpamplona.magpie.spec.MagpieEvents
import com.vitorpamplona.magpie.spec.PublicKeys
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.utils.Hex

/** A key pair for tests, with its npub/nsec forms and a synchronous signer. */
class TestIdentity(
    privKeyHex: String? = null,
) {
    val keyPair: KeyPair = if (privKeyHex != null) KeyPair(Hex.decode(privKeyHex)) else KeyPair()
    val signer = NostrSignerSync(keyPair)
    val pubKey: String = keyPair.pubKey.toHexKey()
    val npub: String = PublicKeys.npub(pubKey)
    val nsec: String = keyPair.privKey!!.toNsec()

    /** Signs [template]; Magpie kinds come back as their Magpie classes. */
    fun <T : Event> sign(template: EventTemplate<T>): Event {
        val event = signer.sign<Event>(template.createdAt, template.kind, template.tags, template.content)
        return MagpieEvents.wrap(event) ?: event
    }

    companion object {
        /** Fixed keys, so golden files and logs are stable across runs. */
        val ALICE = TestIdentity("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a")
        val MALLORY = TestIdentity("3a4c8a7d3f8a2a1c0b0e1e6a4f3c2d1b0a99887766554433221100ffeeddccbb")
    }
}
