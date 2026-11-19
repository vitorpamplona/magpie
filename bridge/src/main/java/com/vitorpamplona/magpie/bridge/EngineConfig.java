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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

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
     * {@code <group>=nip05:<name@domain>}, {@code <group>=namecoin:<releases@name.bit>},
     * or {@code <group>=<npub|hex>}, newline-separated.
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

    // Namecoin NIP-05 resolution
    /** Namecoin backend: {@link #NAMECOIN_BACKEND_DISABLED}, Core RPC, or server-trusting ElectrumX. */
    public static final String NAMECOIN_BACKEND = "namecoinBackend";
    public static final String NAMECOIN_BACKEND_DISABLED = "disabled";
    public static final String NAMECOIN_BACKEND_CORE_RPC = "coreRpc";
    public static final String NAMECOIN_BACKEND_ELECTRUMX = "electrumx";
    /**
     * Namecoin Core JSON-RPC URL. Credentials are read from environment variables instead. Plain
     * HTTP is restricted to loopback; remote endpoints require HTTPS.
     */
    public static final String NAMECOIN_CORE_RPC_URL = "namecoinCoreRpcUrl";
    /** Names, not values, of environment variables holding Core RPC credentials. */
    public static final String NAMECOIN_CORE_RPC_USERNAME_ENV = "namecoinCoreRpcUsernameEnv";
    public static final String NAMECOIN_CORE_RPC_PASSWORD_ENV = "namecoinCoreRpcPasswordEnv";
    public static final String DEFAULT_NAMECOIN_CORE_RPC_USERNAME_ENV = "MAGPIE_NAMECOIN_RPC_USERNAME";
    public static final String DEFAULT_NAMECOIN_CORE_RPC_PASSWORD_ENV = "MAGPIE_NAMECOIN_RPC_PASSWORD";
    /** ElectrumX endpoints, newline-separated: ssl://host:port or tcp://host:port. */
    public static final String NAMECOIN_ELECTRUMX_SERVERS = "namecoinElectrumxServers";
    /** Overall Namecoin lookup and traversal timeout, in milliseconds. */
    public static final String NAMECOIN_LOOKUP_TIMEOUT_MS = "namecoinLookupTimeoutMs";
    /** "true" to try configured ElectrumX servers when Core RPC is unreachable. */
    public static final String NAMECOIN_CORE_RPC_FALLBACK_TO_ELECTRUMX = "namecoinCoreRpcFallbackToElectrumx";
    /** Relay hints for the printed Namecoin value, newline-separated. */
    public static final String NAMECOIN_RELAYS = "namecoinRelays";
    /** Explicit offline 64-hex release pubkey for the print-only Namecoin value task. */
    public static final String NAMECOIN_PUBKEY = "namecoinPubkey";

    // Publishing
    public static final String TASK_PUBLISH = "publish";
    public static final String TASK_CLOSE = "close";
    public static final String TASK_CREATE_SERVER_LIST = "createServerList";
    public static final String TASK_CREATE_RELAY_LIST = "createRelayList";
    public static final String TASK_PRINT_NAMECOIN_VALUE = "printNamecoinValue";

    /** The local Maven repository maven-publish wrote ({@code build/magpie-repo}). */
    public static final String REPO_DIR = "repoDir";
    public static final String GROUP = "group";
    /** The root artifactId (the KMP root module, or the single publication). */
    public static final String ARTIFACT = "artifact";
    public static final String VERSION = "version";
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
     * chain, an explicit {@code namecoin:releases@name.bit} mapping, or {@link #RELEASE_KEY_OFF}.
     */
    public static final String RELEASE_KEY_CHECK = "releaseKeyCheck";
    public static final String RELEASE_KEY_DOMAIN = "domain";
    public static final String RELEASE_KEY_OFF = "off";
    /** "true" to skip publishing events (dry run). */
    public static final String DRY_RUN = "dryRun";

    /**
     * Validates a Core RPC endpoint before it enters Gradle configuration-cache inputs. Errors
     * deliberately never include the configured value, which may contain credential markers.
     */
    public static String validateNamecoinCoreRpcUrl(String configuredUrl) {
        String url = configuredUrl == null ? "" : configuredUrl.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Namecoin Core RPC URL is not a valid absolute URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Namecoin Core RPC URL must use http:// or https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Namecoin Core RPC credentials must use environment variables, not URL user information");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Namecoin Core RPC URL needs a valid host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Namecoin Core RPC URL must not contain a query or fragment");
        }
        int port = uri.getPort();
        if (port == 0 || port < -1 || port > 65535) {
            throw new IllegalArgumentException("Namecoin Core RPC URL has an invalid port");
        }
        if (scheme.equalsIgnoreCase("http") && !isLoopback(host)) {
            throw new IllegalArgumentException(
                    "Namecoin Core RPC plain HTTP is restricted to loopback; use HTTPS for a remote endpoint");
        }
        return url;
    }

    private static boolean isLoopback(String configuredHost) {
        String host = configuredHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int[] values = new int[4];
        try {
            for (int i = 0; i < octets.length; i++) {
                if (octets[i].isEmpty()) {
                    return false;
                }
                values[i] = Integer.parseInt(octets[i]);
                if (values[i] < 0 || values[i] > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return values[0] == 127;
    }
}
