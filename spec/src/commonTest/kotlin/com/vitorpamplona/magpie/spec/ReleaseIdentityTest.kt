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
package com.vitorpamplona.magpie.spec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseIdentityTest {
    /** A tiny public suffix list: enough to exercise the rule. */
    private val suffixes = setOf("com", "org", "io", "co.uk", "github.io")

    private fun registrable(host: String): String? {
        if (host in suffixes) return null
        val labels = host.split('.')
        for (i in labels.indices) {
            val rest = labels.drop(i + 1).joinToString(".")
            if (rest in suffixes) return labels.drop(i).joinToString(".")
        }
        return null
    }

    @Test
    fun walksFromTheGroupDownToTheRegistrableDomain() {
        assertEquals(
            listOf("releases@quartz.vitorpamplona.com", "releases@vitorpamplona.com"),
            ReleaseIdentity.candidates("com.vitorpamplona.quartz", ::registrable),
        )
        assertEquals(listOf("releases@vitorpamplona.com"), ReleaseIdentity.candidates("com.vitorpamplona", ::registrable))
        assertEquals(
            listOf("releases@x.kotlinx.jetbrains.org", "releases@kotlinx.jetbrains.org", "releases@jetbrains.org"),
            ReleaseIdentity.candidates("org.jetbrains.kotlinx.x", ::registrable),
        )
    }

    @Test
    fun publicSuffixesStopTheWalk() {
        assertEquals(listOf("releases@someone.github.io"), ReleaseIdentity.candidates("io.github.someone", ::registrable))
        assertEquals(listOf("releases@lib.example.co.uk", "releases@example.co.uk"), ReleaseIdentity.candidates("uk.co.example.lib", ::registrable))
        // A bare suffix vouches for nobody.
        assertEquals(emptyList(), ReleaseIdentity.candidates("io.github", ::registrable))
        assertEquals(emptyList(), ReleaseIdentity.candidates("com", ::registrable))
    }

    @Test
    fun groupsThatArentDomainsHaveNoCandidates() {
        assertNull(ReleaseIdentity.hostOf("com.my_company.lib"))
        assertEquals(emptyList(), ReleaseIdentity.candidates("com.my_company.lib", ::registrable))
        assertEquals("lib.example.com", ReleaseIdentity.hostOf("com.Example.lib"))
    }

    @Test
    fun configuredIdentitiesMustSitOnTheGroupsDomains() {
        assertTrue(ReleaseIdentity.belongsTo("maven@vitorpamplona.com", "com.vitorpamplona.quartz", ::registrable))
        assertTrue(ReleaseIdentity.belongsTo("releases@quartz.vitorpamplona.com", "com.vitorpamplona.quartz", ::registrable))
        assertFalse(ReleaseIdentity.belongsTo("releases@evil.com", "com.vitorpamplona.quartz", ::registrable))
        assertFalse(ReleaseIdentity.belongsTo("releases@com", "com.vitorpamplona.quartz", ::registrable))
        assertFalse(ReleaseIdentity.belongsTo("releases@other.vitorpamplona.com", "com.vitorpamplona.quartz", ::registrable))
    }
}
