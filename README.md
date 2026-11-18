# Magpie

**Maven artifacts on Nostr.**

Magpie lets Gradle and Kotlin Multiplatform projects publish libraries to
[Blossom](https://github.com/hzrd149/blossom) servers, signed with Nostr events, and use them as if
they came from a normal Maven repository. There is no registry and no server to run. Releases are
immutable, and every file is checked against its signature and hash.

Requires Gradle 8.8 or newer, running on Java 21 or newer (your project can still compile for older
Java with a toolchain).

## Use a library

In `settings.gradle.kts`:

```kotlin
plugins {
    id("com.vitorpamplona.magpie") version "0.1.1"
}

magpie {
    group("com.example")
}
```

Then depend on it as usual:

```kotlin
dependencies {
    implementation("com.example:lib:1.0.0")
}
```

`group("com.example")` trusts the release key that `example.com` names as `releases` in its
`/.well-known/nostr.json`, for that group and its subgroups. For a library whose group isn't a
domain, pin the publisher's key instead and say where to find its relays:

```kotlin
magpie {
    group("org.other") { publisher("npub1…") }
    indexRelays("wss://purplepag.es")
}
```

Commit `gradle/magpie.lock`. It records the trusted key and the exact files of every version you
resolved, and fails the build if either changes later.

## Publish a library

### One-time setup

1. **Pick a release key** and name it in your domain's `/.well-known/nostr.json`, with the relays
   it uses:

   ```json
   {
     "names": { "releases": "<hex pubkey>" },
     "relays": { "<hex pubkey>": ["wss://relay.example.com"] }
   }
   ```

2. **List your Blossom servers** for that key (kind 10063), from any Nostr app that manages media
   servers, or with:

   ```bash
   ./gradlew createNostrServerList --server=https://blossom.example.com
   ```

3. **Give CI a signer.** Set `MAGPIE_BUNKER` to the key's NIP-46 `bunker://…` address. With
   Amethyst as the signer, you approve each release on your phone while it publishes.
   `MAGPIE_NSEC` (an `nsec1…`) also works, but it puts the key itself in CI.

### Each release

In the library's `build.gradle.kts` (any `maven-publish` setup, including Kotlin Multiplatform):

```kotlin
plugins {
    id("com.vitorpamplona.magpie.publish") version "0.1.1"
}

group = "com.example"
version = "1.0.0"
```

```bash
./gradlew publishToNostr
```

It checks that your domain names the signing key and that the version is new, uploads every file,
and signs one release for the whole library. `--dry-run` checks everything without uploading or
signing. A release can't be changed: fix a bad one by publishing a new version.

A Kotlin Multiplatform library built on several CI hosts runs
`./gradlew publishToNostr --partial` on each, then `./gradlew closeNostrRelease --partials=<dir>`
once on one host, which signs the release once every host's files are in.

## More

- [docs/NIP-DRAFT.md](docs/NIP-DRAFT.md): the event format.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how resolution and publishing work.
- [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md): the design decisions and their trade-offs.
- `gateway/`: the same resolver as a Docker service for Maven, sbt or a shared cache.
- [CONTRIBUTING.md](CONTRIBUTING.md): building and testing.

## License

MIT
