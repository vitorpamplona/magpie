# Magpie: Maven artifacts on Nostr

Magpie lets Gradle projects publish libraries to [Blossom](https://github.com/hzrd149/blossom) servers, 
signed with Nostr events, and use them as if they came from a normal Maven repository. 
There is no registry and no server to run. Releases are immutable, and every file is 
checked against its signature and hash.

## Use a library

In `settings.gradle.kts`:

```kotlin
plugins {
    id("com.vitorpamplona.magpie") version "0.2.3"
}

magpie {
    // trusts the release key at `releases@yourdomain.com` for this group and its subgroups.
    group("com.yourdomain")
}
```

Then depend on it as usual:

```kotlin
dependencies {
    implementation("com.yourdomain:yourlibrary:1.0.0")
}
```

If NIP-05 is not available for the domain, you can pin the publisher's key instead and say where to find its relays:

```kotlin
magpie {
    group("org.other") {
        publisher("npub1…")
    }
    // find user's relays via outbox
    indexRelays("wss://purplepag.es")
}
```

Commit `gradle/magpie.lock` to git, like any lock file. It pins each publisher's key and the exact
files of every version you use, so a hijacked domain or a forged release fails your build instead
of reaching it. If it ever fails, check with the publisher before editing it. In CI, add
`-Pmagpie.frozenLock=true` so builds only use what's already in the committed lock.

## Publish a library

### One-time setup

1. **Pick a release key** and name it in your domain's `/.well-known/nostr.json`, with the relays
   it uses:

   ```json
   {
     "names": { "releases": "<hex pubkey>" },
     "relays": {
       "<hex pubkey>": ["wss://your.relay.com"]
     }
   }
   ```

2. **List your Blossom servers** for that key (kind 10063), from any Nostr app that manages media
   servers, or with:

   ```bash
   ./gradlew createNostrServerList --server=https://your.blossom.server
   ```

3. **Give CI a signer.** Set `MAGPIE_BUNKER` to the key's NIP-46 `bunker://…` address or 
   `MAGPIE_NSEC` (an `nsec1…`) also works, but it puts the key itself in CI. With a bunker, you
   approve Magpie's connection once per group (it shows up in your signer as `Magpie: <group>`),
   then each release's signatures. Use a `bunker://` address with a `secret`: Magpie derives its
   side of the connection from it, so every release and every CI runner is that same app, and
   anyone who holds the address can be it too, so keep it in your CI's secrets. Magpie reaches
   the bunker only once the release is checked and ready, so the connection and the signatures
   (one upload authorization per 48 files, and the release itself) arrive together.
   Magpie waits while you work through them; it gives up after 150 s without any answer.

### Each release

In the library's `build.gradle.kts`: 

```kotlin
plugins {
    id("com.vitorpamplona.magpie.publish") version "0.2.3"
}

group = "com.yourdomain"
version = "1.0.0"
```

```bash
./gradlew publishToNostr
```

It checks that your domain names the signing key and that the version is new, uploads every file,
and signs one release for the whole library. `--check-only` checks everything without uploading or
signing. A release can't be changed: fix a bad one by publishing a new version.

A Kotlin Multiplatform library built on several CI hosts runs
`./gradlew publishToNostr --partial` on each, then `./gradlew closeNostrRelease --partials=<dir>`
once on one host, which signs the release once every host's files are in.

## More

- [docs/NIP-DRAFT.md](docs/NIP-DRAFT.md): the event format.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how resolution and publishing work.
- [docs/OPEN-QUESTIONS.md](docs/OPEN-QUESTIONS.md): the design decisions and their trade-offs.
- [CONTRIBUTING.md](CONTRIBUTING.md): building and testing.

## License

MIT
