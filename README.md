# Magpie

**Maven-compatible artifacts on Nostr.**

Magpie lets Gradle and Kotlin Multiplatform projects publish libraries as
[Blossom](https://github.com/hzrd149/blossom) blobs pinned by signed Nostr events, and resolve
them as if from a normal Maven repository. There is no registry and no server to run: blobs live
on the publisher's own Blossom servers, releases are events on any relays, and each consumer pins
the groups it uses to a publisher key.

- Releases are **immutable**: for a `group:artifact:version`, the earliest release event by the
  pinned key wins, and a lock file pins the one you first resolved.
- Every file is checked against its sha256; every event against its signature (in pure Kotlin:
  no native code in your Gradle daemon).
- `-SNAPSHOT` versions, `--offline`, the configuration cache, dynamic versions (`1.+`) and every checksum sidecar Gradle or Maven asks for work.
- A multi-host Kotlin Multiplatform release (Linux + macOS CI) is signed once, after every host's
  files are in and every `.module` reference checks out.

Status: `0.1.0-SNAPSHOT`. Event kinds and tag letters are placeholders until the NIP is assigned
(see [docs/NIP-DRAFT.md](docs/NIP-DRAFT.md)).

## Use a library published with Magpie

`settings.gradle.kts`:

```kotlin
plugins {
    id("com.vitorpamplona.magpie") version "0.1.0"
}

magpie {
    relays("wss://relay.damus.io", "wss://nos.lol")
    group("com.example")                           // the key releases@example.com names (see below)
    group("org.other") { publisher("npub1…") }     // or a key you pin yourself
}
```

Then depend on it as usual: `implementation("com.example:lib:1.0.0")`.

Requires Gradle 8.8 or newer. The plugin starts a Maven repository on `127.0.0.1` inside the Gradle daemon the
first time Gradle needs one of those groups, and registers it with `exclusiveContent`, so those
groups never come from anywhere else.

**Whose releases count.** A groupId is a reversed domain, so the domain vouches for the key, the
way Maven Central ties a namespace to a domain: `group("com.example.lib")` trusts the key that
`releases@lib.example.com` names in its NIP-05 `nostr.json`, or, if that subdomain lists none,
`releases@example.com` (the walk stops at the registrable domain). `releases` and not `_`,
because `_` is usually the domain's general identity. `nip05("maven@example.com")` picks another
name on those domains; `publisher("npub1…")` pins a key with no domain involved (the only option
for a group that isn't a domain). Each group covers its subgroups.

Commit `gradle/magpie.lock`: it records the key each group's domain vouched for and the manifest
(the exact files) of every version you resolved. A domain that later names another key, or a
locked version whose files can't be found, fails the build. Because the lock pins files rather
than event ids, a publisher who rotates keys can re-sign the same manifests and locked builds keep
working. In CI, `-Pmagpie.frozenLock=true` also fails on anything the lock doesn't have yet.

Other settings: `fallbackServers(...)` (Blossom mirrors to try last), `lockFile`, `useLockFile`,
`cacheDir` (default `~/.gradle/magpie`), `relayTimeoutMillis`.

## Publish a library

`build.gradle.kts` of the published project (any `maven-publish` setup, including Kotlin
Multiplatform):

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.vitorpamplona.magpie.publish") version "0.1.0"
}

group = "com.example"
version = "1.0.0"

magpie {
    relays("wss://relay.damus.io", "wss://purplepag.es")   // where your kind 10063 / 10002 live
    archivalRelays("wss://nos.lol")                         // also receive every release
}
```

```bash
export MAGPIE_BUNKER='bunker://…?relay=wss://…&secret=…'   # NIP-46: the key never touches CI
./gradlew publishToNostr
```

`MAGPIE_NSEC` (an `nsec1…`) works too; the bunker wins when both are set. Neither ends up in the
configuration cache: they're read from the environment when the task runs.

`publishToNostr` runs `maven-publish` into `build/magpie-repo`, then:

1. checks that your group's domain vouches for the signing key (`releases@…` in its
   `nostr.json`; the error says exactly what to add), so nobody's build will reject the release;
   `magpie { verifyReleaseKey.set(false) }` skips this for groups that aren't domains;
2. reads your Blossom server list (kind 10063) and write relays (NIP-65); with no server list it
   stops and tells you to run `./gradlew createNostrServerList --server=https://your.blossom.server`;
3. refuses if this version is already released (publish a new version instead);
4. checks every blob against every server (BUD-06 preflight) and stops **before signing** if a
   blob fits on none;
5. uploads what's missing under **one** BUD-02 authorization, uploads the manifest, signs the
   release and publishes it: **two signatures** per release.

A bad release is fixed the Maven Central way: publish a new version. There is no yank.

### Multi-host Kotlin Multiplatform

On each CI host (say Linux for JVM/Android/JS/Linux, macOS for Apple):

```bash
./gradlew publishToNostr --partial --partial-name=linux
```

That uploads the host's blobs and writes `build/magpie/partials/linux.json`. Collect every host's
JSON (as CI artifacts) into one directory on one host and run:

```bash
./gradlew closeNostrRelease --partials=path/to/partials
```

It merges the partial manifests, checks every file every `.module` references is present (a
failed host means missing klibs: it refuses to sign), and signs the release once.

## Gateway

For Maven, sbt or a shared cache, run the same resolver as a service:

```bash
docker build -f gateway/Dockerfile -t magpie-gateway .
docker run -p 8080:8080 -v magpie-cache:/var/cache/magpie \
  -e MAGPIE_RELAYS=wss://relay.damus.io,wss://nos.lol \
  -e MAGPIE_TRUST=com.example=npub1…,org.other=npub1… \
  magpie-gateway
```

Point any Maven client at `http://host:8080/`. The trust policy is the allowlist in
`MAGPIE_TRUST` (keys only, no NIP-05). It serves the same bytes as the local resolver.

## How it works

A **manifest** blob lists every file of a release (`artifact/version/file` → sha256 + size, RFC
8785 canonical JSON). A **release** event (regular kind) pins the manifest's hash and carries one
`j` tag per Maven module (`group:artifact`), a `v` tag (version), `x` tags for the manifest and
primary artifacts, and `server` hints. The resolver queries relays for
`{kinds:[release], authors:[pinned key], #j:[group:artifact], #v:[version]}`, takes the earliest,
fetches the manifest and then the blob, and generates `maven-metadata.xml` and checksums itself.
Details: [docs/NIP-DRAFT.md](docs/NIP-DRAFT.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
and the decisions taken where the design was open: [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md).

Built on [Quartz](https://github.com/vitorpamplona/quartz) (events, signing, the relay client,
NIP-46, NIP-05, the relay server the tests run).

## Repository layout

| Path | |
|---|---|
| `spec/` | Pure Kotlin Multiplatform: kinds and tags (`MagpieSpec`), coordinates, Maven path parsing, manifests, events, winner selection, lock file |
| `bridge/` | Java interfaces between the Gradle plugin and the engine |
| `core/` | The engine: resolver (`resolve/`), publisher (`publish/`), engine entry point |
| `gradle-plugin/` | `com.vitorpamplona.magpie` (settings) and `com.vitorpamplona.magpie.publish` (project) |
| `gateway/` | Ktor service and Dockerfile |
| `testkit/` | In-memory relay, Blossom server and NIP-46 bunker; fixtures |
| `docs/` | NIP draft, architecture, open questions |

## Building and testing

```bash
./gradlew test                                   # spec, testkit, core (incl. golden tests vs Maven Central), gateway
./gradlew :gradle-plugin:functionalTest          # TestKit builds: publish and consume through the plugins
./gradlew :gradle-plugin:functionalTest -Pmagpie.kmpTargets=jvm,android,js,wasmJs,linuxX64
./gradlew :gradle-plugin:functionalTest -Pmagpie.gradleVersion=8.14.3
./gradlew spotlessApply
```

Every test is hermetic: relays, Blossom servers and bunkers run in memory on `127.0.0.1`.
`android` needs `ANDROID_HOME`; Apple targets need macOS (CI runs both).

## License

MIT
