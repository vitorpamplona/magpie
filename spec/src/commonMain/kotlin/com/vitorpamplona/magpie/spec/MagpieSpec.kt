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

/**
 * Every event kind and tag name Magpie uses, in one place. The numbers are placeholders until
 * the NIP is assigned (docs/NIP-DRAFT.md); change them here and nowhere else.
 */
public object MagpieSpec {
    /** Regular (immutable) release event. The earliest one per group:artifact:version wins. */
    public const val KIND_RELEASE: Int = 3071

    /** Addressable snapshot event, `d` = `group:artifact:version` (a -SNAPSHOT version). */
    public const val KIND_SNAPSHOT: Int = 33071

    /** BUD-03 user server list (Blossom). */
    public const val KIND_BLOSSOM_SERVERS: Int = 10063

    /** NIP-65 relay list. */
    public const val KIND_RELAY_LIST: Int = 10002

    /** BUD-02/BUD-11 authorization event. */
    public const val KIND_BLOSSOM_AUTH: Int = 24242

    /**
     * Indexed module key, `<groupId>:<artifactId>`, one per Maven module the event covers (a
     * Kotlin Multiplatform release covers the root module and every target module).
     * No NIP gives `j` a meaning; see docs/NIP-DRAFT.md.
     */
    public const val TAG_MODULE: String = "j"

    /** Indexed version string, exactly as Maven spells it. */
    public const val TAG_VERSION: String = "v"

    /** `["manifest", <sha256>, <size>]`: the manifest blob the event pins. */
    public const val TAG_MANIFEST: String = "manifest"

    /** NIP-94/Blossom sha256 of a blob: the manifest and the primary artifacts. */
    public const val TAG_HASH: String = "x"

    /** Blossom server base URL hint. */
    public const val TAG_SERVER: String = "server"

    /** Addressable identifier (snapshots only). */
    public const val TAG_D: String = "d"

    /** NIP-31 human-readable summary. */
    public const val TAG_ALT: String = "alt"

    /** Releases stay under this many bytes of JSON; secondary files live only in the manifest. */
    public const val MAX_EVENT_BYTES: Int = 16_000

    /** Largest manifest blob a resolver downloads, whatever its event claims. */
    public const val MAX_MANIFEST_BYTES: Long = 16L * 1024 * 1024

    /** Manifest schema version written in the manifest's `schema` field. */
    public const val MANIFEST_SCHEMA: Int = 1

    /** Media type of the manifest blob. */
    public const val MANIFEST_CONTENT_TYPE: String = "application/json"

    /**
     * The NIP-05 name that holds a domain's release key: `releases@<domain>`. Not `_`, which is
     * usually the domain's general identity. See [ReleaseIdentity].
     */
    public const val RELEASES_NIP05_NAME: String = "releases"
}
