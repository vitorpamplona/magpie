# Namecoin release-key discovery

Magpie can use a Namecoin value in place of the NIP-05 discovery document for a `.bit` identity.
The value discovers only the release pubkey and optional relay hints. Signed Nostr release and
snapshot events, Blossom manifests and artifacts, and `gradle/magpie.lock` keep their existing
roles.

For `d/example`, representing `releases@example.bit`:

```json
{"nostr":{"names":{"releases":"<64-hex-release-pubkey>"},"relays":{"<64-hex-release-pubkey>":["wss://relay.example.com"]}}}
```

The two pubkey occurrences must be identical. Magpie requires the exact `releases` entry and does
not accept Quartz's root `_` identity as a fallback. Relay hints are optional. `.bit` relay
endpoints are outside this MVP and are omitted from resolved hints or rejected by the value
printer.

## Consumer configuration

Namecoin is disabled by default. Disabled resolution fails closed and never falls back to HTTPS
for a `.bit` identifier. Ordinary DNS NIP-05 identifiers continue to use the existing HTTPS
resolver.

A reversed `.bit` group follows the normal candidate walk:

```kotlin
magpie {
    group("bit.example.quartz")
    namecoin {
        backend.set("coreRpc")
        coreRpcUrl.set("http://127.0.0.1:8336")
        lookupTimeoutMillis.set(20_000L)
    }
}
```

This tries `releases@quartz.example.bit`, then `releases@example.bit` only after an authoritative
missing or expired name. Quartz handles `map`, `import`, and `delegate` traversal. Malformed
records, failed traversal, timeouts, and unreachable backends remain undecided.

Quartz 2.19.0 reports `NoNostrField` both when an existing record has no exact `releases` entry
and when its `nostr`, `names`, or entry value is malformed. Magpie therefore treats every such
outcome as undecided, not absent. Until Quartz distinguishes these cases, parent fallback is
temporarily unavailable for an existing child record without `releases` or without the selected
`map` entry. This intentional fail-closed restriction prevents a malformed child from authorizing
a parent key during first-use trust or publication.

A legacy DNS-based group is never silently mapped to a `.bit` name. Declare that trust explicitly:

```kotlin
magpie {
    group("com.example") {
        namecoin("releases@example.bit")
    }
}
```

Using `nip05("releases@example.bit")` for `com.example` remains invalid because it is not an
identity on that group's DNS domains.

### Core RPC

The default credential environment variable names are `MAGPIE_NAMECOIN_RPC_USERNAME` and
`MAGPIE_NAMECOIN_RPC_PASSWORD`. Their values are read only from the environment. The Gradle model
contains the variable names, not the credentials. RPC URLs containing user information are
rejected before the URL can enter Gradle configuration-cache inputs. Validation errors do not
include the configured URL. Plain HTTP is accepted only for literal loopback hosts such as
`localhost`, `127.0.0.1`, and `::1`. Remote Core RPC endpoints must use HTTPS.

The names can be changed without changing where the values come from:

```kotlin
magpie {
    namecoin {
        backend.set("coreRpc")
        coreRpcUrl.set("http://127.0.0.1:8336")
        coreRpcUsernameEnv.set("MY_NAMECOIN_USER")
        coreRpcPasswordEnv.set("MY_NAMECOIN_PASSWORD")
    }
}
```

### ElectrumX

Configure explicit `ssl://` or `tcp://` endpoints:

```kotlin
magpie {
    namecoin {
        backend.set("electrumx")
        electrumxServers("ssl://electrumx.example:50002")
        lookupTimeoutMillis.set(20_000L)
    }
}
```

ElectrumX resolution in this MVP is server-trusting, not SPV-verified, even with multiple servers.
Strict per-hop SPV verification is deferred. Core RPC does not fall back to ElectrumX unless both
a server list and this explicit policy are configured:

```kotlin
magpie {
    namecoin {
        backend.set("coreRpc")
        coreRpcUrl.set("http://127.0.0.1:8336")
        electrumxServers("ssl://electrumx.example:50002")
        coreRpcFallbackToElectrumx.set(true)
    }
}
```

## Locks and publication checks

The first successful lookup writes the normal publisher lock with a source such as
`namecoin:releases@example.bit`. Frozen locks reject new entries. Offline resolution can use an
existing locked key. A later Namecoin value that names another key fails loudly; transfer,
expiration, renewal, or re-registration cannot silently rotate the locked key.

Publication always requires a successful current exact-key check. It never uses the consumer's
locked-key fallback. The default check works for a reversed `.bit` group. A legacy group uses the
same explicit identity on the publishing side:

```kotlin
magpie {
    releaseKeyNamecoin.set("releases@example.bit")
    namecoin {
        backend.set("coreRpc")
        coreRpcUrl.set("http://127.0.0.1:8336")
    }
}
```

Consumer resolution and publication checks use the same `Nip05ResolverFactory` and Namecoin
connection policy.

## Print a value without publishing

Configure relay hints in the publishing extension, then print the deterministic compact value:

```kotlin
magpie {
    namecoinRelays("wss://relay.example.com")
}
```

```bash
./gradlew printMagpieNamecoinValue
```

Relay options can instead be repeated on the command line:

```bash
./gradlew printMagpieNamecoinValue \
  --relay=wss://one.example.com \
  --relay=wss://two.example.com/nostr
```

With a local secret key, the task derives the release pubkey locally. A NIP-46 bunker is never
contacted. When the selected signer is a bunker, supply the release pubkey explicitly:

```bash
./gradlew printMagpieNamecoinValue --pubkey=<64-hex-release-pubkey>
```

The explicit `--pubkey` takes precedence over every signer. Bunker-only configuration without it
fails before opening a relay subscription or writing a NIP-46 client-key file. The bunker
transport pubkey is never substituted for the signing identity.

The task prints only the value. It does not open a relay subscription, publish a Nostr event or
NIP-46 request, upload a Blossom blob, or create a Namecoin transaction. Update `d/example`
separately with Namecoin Core or a wallet.

The renderer enforces a 520 UTF-8-byte interoperable UI design budget before printing. This is not
the 1,023-byte consensus bound. Invalid keys or relays, `.bit` relay endpoints, too many hints,
and oversized values reject the whole result. Names and relays are never silently dropped to make
a value fit.
