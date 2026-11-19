# Architecture

Magpie turns Nostr events and Blossom blobs into a Maven repository on demand. There is no
server to run: a consumer's Gradle daemon runs the resolver, a publisher's Gradle build runs the
publisher.

```
                publishToNostr                                   settings plugin
 maven-publish ───────────────▶ Blossom servers (kind 10063) ◀──── local repository
 build/magpie-repo   upload      blobs by sha256                 http://127.0.0.1:<port>/
        │                                ▲                              ▲
        │ sign release (kind 3071)       │ GET /<sha256>                │ GET …/lib-1.0.jar
        ▼                                │                              │
   the key's relays (NIP-65 write,  ──── REQ #j #v, authors=[pinned] ── Gradle
   nostr.json)                                                            │
                                         kind 10002 of the key ◀──────────┘
                                         on index relays + nostr.json relays
```

## Modules

| Module | What | Depends on |
|---|---|---|
| `spec/` | Kotlin Multiplatform `commonMain`, no I/O: `MagpieSpec` (every kind and tag), `Coordinate`/`ModuleId`, `MavenPath` (parses `…/artifact/version/file` from the end), `Manifest` (RFC 8785 canonical JSON, merge of partials), `MagpieReleaseEvent`/`MagpieSnapshotEvent` (builders and readers), `WinnerSelection`, `EventVerification` (pure-Kotlin Schnorr), `LockFile` | Quartz |
| `bridge/` | Java interfaces between the plugin and the engine, JDK types only | — |
| `core/` | The engine (JVM). `resolve/`: `RelayQuery`, `BlobFetcher`/`BlobStore`, `Resolver` (release/snapshot/manifest/blob lookups and caches), `TrustPolicy`/`LockStore`, `MetadataGenerator`, `ChecksumFiles`, `Router`, `LocalRepoServer`, `RepositoryMirror`. `publish/`: `RepoScanner`, `ModuleChecker`, `BlossomClient` (uploader), `MagpieSigner` (nsec, NIP-46), `Publisher` (publish, partial, close, server list). `engine/`: `MagpieEngineImpl` (the bridge entry point), `ResolverStack` | spec, Quartz, OkHttp, coroutines |
| `gradle-plugin/` | `MagpieSettingsPlugin` (resolve), `MagpiePublishPlugin` (publish), `EngineLoader`, `MagpieResolverService` | bridge (embedded), core (nested jars) |
| `testkit/` | In-memory relay (Quartz `NostrServer` + in-memory SQLite, over Ktor websockets), in-memory Blossom (BUD-01/02/06), `TestBunker` (NIP-46 via Quartz's signer service), `FakeMavenRepo`, `TestIdentity` | spec, Quartz, Ktor |

### What Quartz provides, and what Magpie adds

Quartz (`com.vitorpamplona.quartz:quartz:2.15.1` from Maven Central) supplies events, ids and
signing (`Event`, `EventHasher`, `NostrSignerInternal`), the pure-Kotlin secp256k1
(`Secp256k1InstanceKotlin`), the relay client (`NostrClient`, `fetchAll`,
`publishAndCollectResults`, OkHttp websockets), NIP-46 (`NostrSignerRemote`, and the bunker side
`NostrConnectSignerService` used in tests), NIP-05 (`Nip05Client`), NIP-19, RFC 8785
(`JsonCanonicalization`), Blossom URL handling, and the relay server and store the testkit runs.
Magpie adds the Maven-specific parts: the event kinds, manifests, path parsing, winner selection,
metadata generation, the resolver's caching and trust, and the Blossom upload flow (multi-hash
BUD-02 authorization, BUD-06 preflight), which Quartz doesn't have as a client.

## Resolving

1. The settings plugin reads `magpie { … }` once settings are evaluated and registers, inside
   `exclusiveContent` filtered to the configured groups, a Maven repository whose URL is a
   provider of `MagpieResolverService.url`. No network at configuration time.
2. When Gradle first needs a Magpie group, it evaluates the URL: the build service loads the
   engine (see *Class loading*) and starts `LocalRepoServer`, a JDK `HttpServer` on `127.0.0.1`,
   on a port derived from the cache directory when it's free (so the URL, and Gradle's cache of
   it, repeat across builds), under a path prefix derived from the configuration (so a build can
   never reach a server another daemon started with different trust settings).
3. Per request, `Router`:
   - parses the path (`MavenPath`); unknown groups are 404;
   - finds the trusted key (`TrustPolicy`): by default `releases@<domain>` down the group's
     domain chain (`ReleaseIdentity`, `HttpNip05Resolver`), pinned in `gradle/magpie.lock`, where
     a later change fails loudly; or a configured NIP-05 on those domains, or an npub. The
     `relays` the domain's `nostr.json` lists for the key are kept (`RelayHints`, also on disk);
   - finds the key's relays (`RelayQuery.relaysFor`, the outbox model): the write relays of its
     newest kind 10002, looked up on the index relays and the `nostr.json` relays (10 min cache,
     disk fallback), plus the `nostr.json` relays; the index relays only when both are empty.
     Every query below goes to those relays;
   - for a file: finds the release (`Resolver.release`: disk cache → relays, honoring the lock,
     earliest wins) or the newest snapshot; fetches the manifest (content-addressed, cached
     forever); looks up the file; fetches the blob from the release's server hints, then the
     publisher's current kind 10063, then fallbacks, checking its sha256 while streaming;
   - for a checksum sidecar: computes it from the blob (cached next to it); `.sha256` is the blob
     name itself;
   - for `maven-metadata.xml`: lists releases and snapshots of the module (30 s cache) and
     formats them like Maven Central does.
4. Misses are remembered for 30 s; manifests and blobs never expire; a resolved release is
   reused forever (and pinned in the lock).
5. Everything served is also linked into `~/.gradle/magpie/mirror/<publisher>/`, a plain Maven
   layout that `--offline` builds use as a `file:` repository.

### Caches (`~/.gradle/magpie/`)

| Path | Holds | Lifetime |
|---|---|---|
| `engine/<id>/` | the engine's jars, extracted from the plugin | per plugin version |
| `blobs/<aa>/<sha256>` | every blob (artifacts and manifests), verified | forever |
| `checksums/` | computed `.md5`/`.sha1`/`.sha512` | forever |
| `events/<publisher>/<group>/<artifact>/<version>.json` | every candidate release seen for that version (each project selects with its own lock), or the latest snapshot | forever (snapshots: refreshed) |
| `events/<publisher>/<group>/<artifact>/_module.json` | the last module listing, for offline metadata | refreshed every 30 s online |
| `servers/<publisher>.json` | the publisher's kind 10063 | refreshed every 10 min online |
| `relay-lists/<publisher>.json` | the publisher's kind 10002 | refreshed every 10 min online |
| `nip05-relays/<publisher>.txt` | the relays its domain's `nostr.json` lists for it | replaced on every lookup |
| `mirror/<publisher>/…` | Maven layout for `--offline` | grows as files are served |

## Publishing

`publishToNostr` depends on `maven-publish` writing `build/magpie-repo` (emptied first), then
runs in a worker process:

1. `RepoScanner` hashes every file of the group and version (skipping Gradle's sidecars and
   `maven-metadata.xml`, keeping `.asc`) into a `Manifest`.
2. `ReleaseKeyCheck` makes sure the group's domain names the signing key (`releases@` down the
   domain chain), so no consumer will reject the release, and returns the relays `nostr.json`
   lists for it. The publisher's kind 10002 is read from the index relays and those relays, then
   its kind 10063 also from the write relays the 10002 names; no 10063 → a failure that names
   `createNostrServerList`.
3. `ModuleChecker` makes sure every file each `.module` references (and every `available-at`
   module) is in the manifest.
4. Relays are asked for an existing release of any of the modules at this version by this key;
   one exists → refuse (releases are immutable).
5. Every server is asked `HEAD /<sha256>` for every blob; missing ones get a BUD-06
   `HEAD /upload` preflight. A blob no server would take fails the publish, **before anything is
   signed**.
6. Every signature is requested at once, in parallel, before any upload; a remote signer queues
   them, so a person approving sees them in a row, and the NIP-46 client matches each answer to
   its request by id. They are kind 24242 authorizations covering the missing blobs (and the
   manifest), one per 48 hashes (`MAX_HASHES_PER_AUTH`) so the `Authorization` header stays under
   nginx's 8 KB default, then the release, with the server hints the checks predict.
7. The blobs upload in parallel; a server that refuses the authorizations gets one per blob. If a
   server the release names failed its uploads, the release is signed again without it.
8. The release is published to the key's relays (write relays and the
   `nostr.json` relays), where consumers read it; at least one must accept. A key with no relays
   of its own is published to the index relays instead, where consumers then look, with a
   warning that names `createNostrRelayList`.

### Multi-host Kotlin Multiplatform

Each CI host runs `publishToNostr --partial`: steps 1, 2, 4 and 5, then 6 and 7 without the
release, for its own files; then it writes `build/magpie/partials/<host>.json` instead of signing a release. CI collects those files
on one host, which runs `closeNostrRelease`: merge (conflicting hashes fail), check every blob is
on a server, fetch the `.module` files and run `ModuleChecker` over the merged set (a missing
host's klibs fail here), upload the manifest, sign once.

### Signers

`MAGPIE_BUNKER` (a NIP-46 `bunker://` URI; the key never touches CI) wins over `MAGPIE_NSEC`.
The NIP-46 client key is kept in Magpie's cache (`bunker-clients/<bunker pubkey>.key`, owner-only),
so every task and build on a machine is the client the bunker already paired with.
Both are read from the environment when the task runs, never stored in the configuration cache.

## Class loading in the Gradle daemon

The plugin jar holds Gradle-facing code and the `bridge` interfaces. The engine (core, spec,
Quartz, OkHttp, okio, coroutines, serialization, its own Kotlin 2.4 stdlib) is nested under
`META-INF/magpie/engine/` with an index of hashes. `EngineLoader` loads it with a
`URLClassLoader` whose parent sees only the JDK and `com.vitorpamplona.magpie.bridge.*`, so:

- no other plugin's OkHttp/serialization/coroutines can clash with Magpie's (what shading would
  give), and
- the engine runs on its own Kotlin stdlib whatever Gradle embeds (which relocation can't give).

Configuration crosses as `Map<String, String>` (keys in `EngineConfig`). The daemon's engine has
no secp256k1 JNI: resolution verifies BIP-340 signatures with Quartz's pure-Kotlin secp256k1.
Signing happens in a worker process (`processIsolation`), which adds the JNI jars.

The loader never checks the file system before it must: it probes by loading the engine class
and extracts atomically only if that fails. Gradle instruments plugin code during configuration,
and a check there would become a configuration-cache input.

## Extension points

- `TrustPolicy`/`Pin`: other ways to decide whose events count (web of trust, …).
- `MagpieSigner`: other signers (NIP-55, hardware).
- `Router`: other front ends (an HTTP service for Maven or sbt, say).
- `RelayQuery`/`MagpieSpec`: more event kinds (timestamps, attestations).
