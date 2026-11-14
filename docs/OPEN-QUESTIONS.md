# Open questions

Each entry is a point the brief left open, the option Magpie took (the most conservative one:
immutability and safety first) and what would change it. Items marked **decide before release**
are hard to reverse once releases exist.

## Protocol

1. **Kind numbers and tag letters** (**decide before release**). Placeholders: release `3071`,
   snapshot `33071`; tags `j` (module) and `v` (version). All in
   `spec/src/commonMain/kotlin/com/vitorpamplona/magpie/spec/MagpieSpec.kt`. Reasoning in
   `NIP-DRAFT.md`. Once real releases exist, changing them strands every published version.

2. **One release per library, not per Maven module.** A Kotlin Multiplatform library is a dozen
   Maven modules sharing a version. "Two signatures per release" and "closeNostrRelease verifies
   every `.module` reference" only work if one event covers them all, so a release carries one `j`
   tag per module and manifest keys are `artifact/version/file`. The manifest still has the brief's
   shape (`coordinate`, `files`), plus `schema`.

3. **Group matching.** `group("com.example")` trusts that group *and its subgroups*
   (`com.example.lib`), matching Gradle's `includeGroupAndSubgroups`; the most specific rule
   wins. The brief's example (`group("com.vitorpamplona")` for artifacts in
   `com.vitorpamplona.quartz`) needs this. Exact-group-only would be stricter but would make that
   example useless.

4. **Default trust: the group's domain vouches.** `group("com.example.lib")` with no block trusts
   the key at `releases@lib.example.com`, else `releases@example.com` (down to the registrable
   domain, by OkHttp's public suffix list). `releases`, not `_`, which is usually the domain's
   general identity. Only a definite "none" falls through to a parent domain; an unreachable one
   decides nothing (the lock carries the build, or it fails). `nip05("name@domain")` must name a
   domain on that chain; `publisher(npub)` pins a key with no domain. `publishToNostr` refuses to
   sign with a key the domain doesn't name (`verifyReleaseKey.set(false)` to skip). Open: whether
   the walk should stop at the most specific domain that *exists* rather than one that lists
   `releases` (stricter for organisations that delegate subdomains to strangers).

5. **No yanks.** A yank kind was built and then dropped: it couldn't keep a version out of the
   `<versions>` list Gradle's `1.+` reads, a leaked key could abuse it, and the fix for a bad
   release is a new version. If deprecation comes back, it should behave like Cargo's yank
   (left out of `<versions>`, still served to exact and locked requests). Adding a kind later
   breaks nothing.

6. **A later release of the same version.** The publisher refuses to sign one. If one appears
   anyway (another tool, a compromised key backdating `created_at`), clients keep the earliest,
   and the lock file pins the manifest first resolved, so a backdated release can't take over a
   locked build. Without a lock, a backdated release *does* win: that is what earliest-wins means.
   The lock is on by default for that reason.

7. **Release lookups are cached forever.** Once a release has been resolved (and checked), the
   engine never asks relays about that version again, even across daemons. Only module listings
   (`maven-metadata.xml`) and snapshots expire (30 s).

8. **Negative cache scope.** Misses (no release, file not in the manifest, unknown group) are
   remembered for 30 s per daemon; manifests and blobs are never negative-cached. A query no
   relay answered is not a miss: it's a 502 (or the disk cache, when it has an answer), and is
   never remembered.

## Gradle

9. **Shading vs. an isolated class loader.** The brief asks to shade and relocate OkHttp,
   serialization etc. Relocation can't fix the bigger clash: Quartz is compiled with Kotlin 2.4
   and needs that stdlib, while Gradle 8 daemons run plugins on Kotlin 2.0's. Relocating the Kotlin
   stdlib itself breaks Kotlin metadata and reflection. So the plugin jar nests the engine's jars
   (`META-INF/magpie/engine/`), extracts them once to `~/.gradle/magpie/engine/<hash>/`, and loads
   them in a class loader that sees only the JDK and the bridge interfaces. Same isolation as
   shading, stronger for the stdlib, no bytecode rewriting. Cost: a 21 MB plugin jar (Quartz is
   16 MB of it), and one engine in the daemon's metaspace per project that resolves through
   Magpie: Gradle gives each project's settings their own plugin class loader, and the engine
   stays loaded after the build (repeated builds of a project reuse it). A daemon that has
   served many such projects can run short of metaspace, which Gradle handles by retiring it.
   Open: one engine per daemon, behind a bridge of JDK types only, so it could be shared across
   plugin class loaders.

10. **The secp256k1 JNI.** Excluded from the daemon's engine; resolution verifies with Quartz's
    pure-Kotlin secp256k1. Publishing signs in a Gradle worker *process*, the only place the JNI
    loads. ACINQ's JNI 0.24.0 targets Java 21, so the worker uses a Java 21 toolchain when the
    daemon runs on 17. Resolution needs a daemon on Java 21: Quartz's pure-Kotlin secp256k1 calls
    `Math.unsignedMultiplyHigh` (Java 18), and current Quartz targets Java 21 outright. The
    settings plugin says so at configuration on an older daemon.

11. **When the server starts.** The repository URL is a provider; Gradle evaluates it when it
    first resolves a Magpie group, which can be during configuration-cache *storing*. No relay or
    Blossom request happens then (Gradle has only asked for the URL), but the engine is loaded.
    The loader never reads the file system unless it must extract, so the configuration cache
    records nothing from it. On a configuration-cache hit Gradle reuses its stored, already
    downloaded artifacts and doesn't contact the repository at all; if it ever had to (artifacts
    evicted from `~/.gradle/caches`), it would use the stored URL of a server that is gone, and the
    fix is `--refresh-dependencies` or a run without the cache.

12. **`--offline`.** Gradle never calls an HTTP repository offline, not even on localhost, and a
    repository whose port can change has no stable identity in Gradle's cache. So offline builds
    resolve from a plain Maven directory the engine fills as it serves
    (`~/.gradle/magpie/mirror/<publisher hex>/`), registered as a `file:` repository inside the
    same `exclusiveContent`. One tree per publisher keeps groups that trust different keys apart.

13. **Projects with their own `repositories {}`.** Unless the build uses
    `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, a project that declares repositories ignores the
    settings' ones, so the plugin adds the Magpie repository to such projects after they're
    evaluated.

14. **Minimum Gradle version.** 8.8: earlier versions generate no Kotlin DSL accessor for the
    `magpie { }` settings extension (8.6/8.7 can only use `configure<MagpieExtension> { }`). CI
    runs the end-to-end tests on 8.8, 8.14 and 9.8.2 (the repository's wrapper), and checks that
    a daemon on JDK 17 fails at configuration with a hint.

15. **Lock file writes.** The engine writes `gradle/magpie.lock` during resolution (one writer at
    a time, atomic replace). CI can freeze it (`-Pmagpie.frozenLock=true`): any new entry then
    fails the build.

## Publishing

16. **Where to publish.** The publisher's own relays (NIP-65 write relays and the relays its
    domain's `nostr.json` lists for it) plus `archivalRelays`. A key with no relays of its own
    goes to the index relays, because that is where consumers look for such a key. At least one
    relay must accept the event.

17. **Which servers go in `server` hints.** The servers that ended up holding every blob; if none
    holds all of them, every server that holds any.

18. **Uploading everywhere.** Blobs go to every server in the 10063 list that accepts them (more
    copies, more availability). A server failure is a warning as long as each blob is on at least
    one server.

19. **Partial manifests travel by CI artifact.** `publishToNostr --partial` writes
    `build/magpie/partials/<host>.json`; CI moves them to the closing job's
    `magpie.partialsDir`. Close re-checks that every listed blob is on a server before signing.

20. **Snapshot overwrite checks.** None: a snapshot is meant to be replaced.

21. **No default relays.** Magpie ships no relay list: a default would make those relays
    everyone's dependency. Each key's events live on its own relays (the outbox model), found
    through its NIP-65 list on the `indexRelays` a build names, or through the `relays` its
    domain's `nostr.json` lists. A group trusted through its domain can need no relay
    configuration at all. Open: whether to also read NIP-05's `nip46` relays, and whether a
    key's relay list should be pinned in the lock (today it is refreshed, since moving relays is
    routine and the events are signed either way).

## Deferred (out of scope for v1)

OpenTimestamps verification, SwiftPM `binaryTarget` output, npm, web-of-trust policies, and
proxying Maven Central. Extension points: `TrustPolicy` (resolution), `MagpieSigner`
(publishing), `Router` (anything that serves a Maven layout), and `RelayQuery` (more event kinds).
