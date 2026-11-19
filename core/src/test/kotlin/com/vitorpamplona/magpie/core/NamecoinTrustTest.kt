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
package com.vitorpamplona.magpie.core

import com.vitorpamplona.magpie.core.publish.ReleaseKeyCheck
import com.vitorpamplona.magpie.core.publish.ReleaseKeyMismatchException
import com.vitorpamplona.magpie.core.resolve.GroupRule
import com.vitorpamplona.magpie.core.resolve.LockStore
import com.vitorpamplona.magpie.core.resolve.MagpieSecurityException
import com.vitorpamplona.magpie.core.resolve.Nip05Answer
import com.vitorpamplona.magpie.core.resolve.Nip05Resolver
import com.vitorpamplona.magpie.core.resolve.TrustPolicy
import com.vitorpamplona.magpie.spec.LockFile
import com.vitorpamplona.magpie.spec.LockedPublisher
import com.vitorpamplona.magpie.testkit.TestIdentity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NamecoinTrustTest {
    private val alice = TestIdentity.ALICE.pubKey
    private val mallory = TestIdentity.MALLORY.pubKey
    private val dir = Files.createTempDirectory("magpie-namecoin-trust").toFile()
    private val lockFile = File(dir, "magpie.lock")
    private val log = RecordingLog()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class Table(
        val answers: MutableMap<String, Nip05Answer>,
    ) : Nip05Resolver {
        val asked = mutableListOf<String>()

        override suspend fun lookup(identifier: String): Nip05Answer {
            asked += identifier
            return answers[identifier] ?: Nip05Answer.Absent
        }
    }

    private fun policy(
        table: Table,
        frozen: Boolean = false,
        offline: Boolean = false,
    ) = TrustPolicy(
        listOf(GroupRule.parse("bit.example.quartz=domain")),
        LockStore(lockFile, frozen, log),
        table,
        offline,
        log,
    )

    private fun publisherOf(policy: TrustPolicy): String = runBlocking { policy.publisherFor("bit.example.quartz")!!.pubKey }

    private fun lock(
        key: String = alice,
        source: String = "namecoin:releases@example.bit",
    ) {
        lockFile.writeText(LockFile().withPublisher("bit.example.quartz", LockedPublisher(key, source)).render())
    }

    @Test
    fun bitCandidatesWalkFromMapSubdomainToApexAndRecordNamecoinSource() {
        val table = Table(mutableMapOf("releases@example.bit" to Nip05Answer.Key(alice)))
        assertEquals(alice, publisherOf(policy(table)))
        assertEquals(listOf("releases@quartz.example.bit", "releases@example.bit"), table.asked)
        assertEquals(
            LockedPublisher(alice, "namecoin:releases@example.bit"),
            LockFile.parse(lockFile.readText()).publishers.getValue("bit.example.quartz"),
        )
    }

    @Test
    fun aFailedChildNeverFallsThroughToTheApexKey() {
        lock()
        val table =
            Table(
                mutableMapOf(
                    "releases@quartz.example.bit" to Nip05Answer.Unreachable("import failed"),
                    "releases@example.bit" to Nip05Answer.Key(mallory),
                ),
            )
        assertEquals(alice, publisherOf(policy(table)))
        assertEquals(listOf("releases@quartz.example.bit"), table.asked)

        lockFile.delete()
        assertFailsWith<MagpieSecurityException> { publisherOf(policy(table)) }
        assertEquals(listOf("releases@quartz.example.bit", "releases@quartz.example.bit"), table.asked)
    }

    @Test
    fun lockedNamecoinKeysCannotRotate() {
        lock()
        val table = Table(mutableMapOf("releases@example.bit" to Nip05Answer.Key(mallory)))
        val error = assertFailsWith<MagpieSecurityException> { publisherOf(policy(table)) }
        assertTrue("PUBLISHER KEY CHANGED" in error.message!!, error.message)
        assertEquals(
            alice,
            LockFile
                .parse(lockFile.readText())
                .publishers
                .getValue("bit.example.quartz")
                .pubKey,
        )
    }

    @Test
    fun frozenLocksRejectNewNamecoinEntries() {
        val table = Table(mutableMapOf("releases@example.bit" to Nip05Answer.Key(alice)))
        assertFailsWith<MagpieSecurityException> { publisherOf(policy(table, frozen = true)) }
        assertTrue(!lockFile.isFile || LockFile.parse(lockFile.readText()).publishers.isEmpty())
    }

    @Test
    fun offlineAndAuthoritativeAbsenceOnlyUseAnExistingLock() {
        val table = Table(mutableMapOf())
        assertFailsWith<MagpieSecurityException> { publisherOf(policy(table)) }

        lock()
        assertEquals(alice, publisherOf(policy(table)))
        val offlineTable = Table(mutableMapOf())
        assertEquals(alice, publisherOf(policy(offlineTable, offline = true)))
        assertEquals(emptyList(), offlineTable.asked)
    }

    @Test
    fun publicationAlwaysNeedsTheCurrentExactKey() =
        runBlocking {
            val table = Table(mutableMapOf("releases@example.bit" to Nip05Answer.Key(alice)))
            assertEquals(emptyList(), ReleaseKeyCheck(table).verify("bit.example", alice))

            table.answers["releases@example.bit"] = Nip05Answer.Key(mallory)
            assertFailsWith<ReleaseKeyMismatchException> { ReleaseKeyCheck(table).verify("bit.example", alice) }

            table.answers["releases@example.bit"] = Nip05Answer.Unreachable("offline")
            assertFailsWith<ReleaseKeyMismatchException> { ReleaseKeyCheck(table).verify("bit.example", alice) }

            table.answers["releases@example.bit"] = Nip05Answer.Absent
            assertFailsWith<ReleaseKeyMismatchException> { ReleaseKeyCheck(table).verify("bit.example", alice) }
            Unit
        }
}
