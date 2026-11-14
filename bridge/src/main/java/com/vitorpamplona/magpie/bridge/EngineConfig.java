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
package com.vitorpamplona.magpie.bridge;

/** Keys of the configuration maps the plugin hands the engine. Lists are newline-separated. */
public final class EngineConfig {
    private EngineConfig() {}

    public static final String ENGINE_CLASS = "com.vitorpamplona.magpie.core.engine.MagpieEngineImpl";

    // Resolution
    /**
     * Index relays, newline-separated: where keys' NIP-65 relay lists (kind 10002) are looked up.
     * Each key's events are then read from its own write relays and the relays its domain's
     * nostr.json lists for it; these relays only when a key has neither. Publishing reads its own
     * lists here too.
     */
    public static final String INDEX_RELAYS = "indexRelays";
    /**
     * {@code <group>=domain} (the group's domain vouches: {@code releases@<domain>}),
     * {@code <group>=nip05:<name@domain>} or {@code <group>=<npub|hex>}, newline-separated.
     */
    public static final String GROUPS = "groups";
    /** Blossom servers tried after the release's hints and the publisher's kind 10063. */
    public static final String FALLBACK_SERVERS = "fallbackServers";
    /** Directory for manifests, blobs and event caches ({@code ~/.gradle/magpie}). */
    public static final String CACHE_DIR = "cacheDir";
    /** Path of {@code gradle/magpie.lock}, or empty to disable the lock. */
    public static final String LOCK_FILE = "lockFile";
    /** "true" to serve only from the cache. */
    public static final String OFFLINE = "offline";
    /** Negative-cache lifetime for misses, in milliseconds. */
    public static final String NEGATIVE_TTL_MS = "negativeTtlMs";
    /** Idle timeout for relay queries, in milliseconds. */
    public static final String RELAY_TIMEOUT_MS = "relayTimeoutMs";
    /** Testing hook: NIP-05 lookups go to this base URL ({@code {domain}} stands for the domain). */
    public static final String NIP05_BASE_URL = "nip05BaseUrl";
    /** "true" to refuse to change the lock (CI): new entries fail the build instead. */
    public static final String LOCK_FROZEN = "lockFrozen";

    // Publishing
    public static final String TASK_PUBLISH = "publish";
    public static final String TASK_CLOSE = "close";
    public static final String TASK_CREATE_SERVER_LIST = "createServerList";
    public static final String TASK_CREATE_RELAY_LIST = "createRelayList";

    /** The local Maven repository maven-publish wrote ({@code build/magpie-repo}). */
    public static final String REPO_DIR = "repoDir";
    public static final String GROUP = "group";
    /** The root artifactId (the KMP root module, or the single publication). */
    public static final String ARTIFACT = "artifact";
    public static final String VERSION = "version";
    /** Relays to publish to besides the publisher's NIP-65 write relays (archival relays). */
    public static final String PUBLISH_RELAYS = "publishRelays";
    /** NIP-46 bunker URI. Takes precedence over the nsec. */
    public static final String BUNKER_URI = "bunkerUri";
    /** nsec (or hex) secret key, from an environment variable. */
    public static final String NSEC = "nsec";
    /** Where {@code --partial} writes its partial manifest, and where close reads them from. */
    public static final String PARTIAL_DIR = "partialDir";
    /** A name for this host's partial manifest. */
    public static final String PARTIAL_NAME = "partialName";
    /** "true" for {@code publishToNostr --partial}. */
    public static final String PARTIAL = "partial";
    /** Blossom servers for {@link #TASK_CREATE_SERVER_LIST}. */
    public static final String SERVERS = "servers";
    /** createRelayList: the relays to list, newline-separated. */
    public static final String LIST_RELAYS = "listRelays";
    /**
     * Which key the group's domain must vouch for before publishing: {@link #RELEASE_KEY_DOMAIN}
     * (default: {@code releases@} down the group's domain chain), a NIP-05 identifier on that
     * chain, or {@link #RELEASE_KEY_OFF}.
     */
    public static final String RELEASE_KEY_CHECK = "releaseKeyCheck";
    public static final String RELEASE_KEY_DOMAIN = "domain";
    public static final String RELEASE_KEY_OFF = "off";
    /** "true" to skip publishing events (dry run). */
    public static final String DRY_RUN = "dryRun";
}
