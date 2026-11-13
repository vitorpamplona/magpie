NIP-XX
======

Maven Artifacts
---------------

`draft` `optional`

This NIP lets a publisher distribute Maven artifacts (jars, aars, klibs, POMs, Gradle module
metadata, sources, javadoc, signatures) as [Blossom](https://github.com/hzrd149/blossom) blobs,
pinned by signed events, so that any client can rebuild a Maven repository for them without a
central server.

All kind numbers below are **placeholders** until assigned. In the reference implementation
they live in one file (`spec/…/MagpieSpec.kt`).

## Overview

- Every file of a release is a Blossom blob, addressed by its sha256 and hosted on the
  publisher's own servers ([BUD-03](https://github.com/hzrd149/blossom/blob/master/buds/03.md),
  kind `10063`).
- A **manifest** blob maps every Maven file name of the release to its sha256 and size.
- A **release** event (regular kind `3071`) pins the manifest. Releases are immutable.
- A **snapshot** event (addressable kind `33071`) pins the manifest of a `-SNAPSHOT` version.

## Manifest

A JSON object, serialized canonically with [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)
(sorted keys, no insignificant whitespace, UTF-8), so the same file table always hashes to the
same blob:

```json
{"coordinate":"com.example:lib:1.0.0","files":{"lib-jvm/1.0.0/lib-jvm-1.0.0.jar":{"sha256":"…","size":1234},"lib/1.0.0/lib-1.0.0.module":{"sha256":"…","size":567}},"schema":1}
```

- `schema`: integer, currently `1`. Readers MUST refuse a manifest with a higher schema.
- `coordinate`: `groupId:artifactId:version` of the release's root module.
- `files`: keyed by `<artifactId>/<version>/<file name>`, the path below the group directory of a
  Maven repository. One release can span several Maven modules that share a group and version,
  as a Kotlin Multiplatform library does (`lib`, `lib-jvm`, `lib-iosarm64`, …). Every key's
  version MUST equal the coordinate's version, and every file name MUST start with
  `<artifactId>-<version>` (or, for snapshots, `<artifactId>-<base>-<yyyyMMdd.HHmmss>-<n>`).
- Checksum sidecars (`.md5`, `.sha1`, `.sha256`, `.sha512`) and `maven-metadata.xml` are NOT
  listed: clients compute them. Signatures (`.asc`) are listed like any other file.

Readers ignore unknown fields.

## Release (kind `3071`)

```json
{
  "kind": 3071,
  "content": "",
  "tags": [
    ["j", "com.example:lib"],
    ["j", "com.example:lib-jvm"],
    ["j", "com.example:lib-linuxx64"],
    ["v", "1.0.0"],
    ["manifest", "<manifest sha256>", "<manifest size>"],
    ["x", "<manifest sha256>"],
    ["x", "<sha256 of a primary artifact>"],
    ["server", "https://blossom.example.com"],
    ["alt", "Release of com.example:lib:1.0.0 (Maven artifacts on Nostr)"]
  ]
}
```

- `j` (one per Maven module in the manifest, root first): `<groupId>:<artifactId>`.
- `v`: the version, exactly as Maven spells it.
- `manifest`: the manifest blob's sha256 and size in bytes.
- `x`: the manifest's hash, then the hashes of primary artifacts: main jars/aars/klibs (no
  classifier, or a cinterop one), POMs and `.module` files, so a `#x` query answers "who signed
  this file". Secondary files (sources, javadoc, signatures, tooling metadata) are only in the
  manifest. Publishers drop trailing primary `x` tags to keep the event under 16 KB.
- `server`: Blossom servers the publisher uploaded to (hints; clients also use the publisher's
  current kind `10063`).
- `alt`: [NIP-31](31.md).

### Canonical release

For a module `G:A` and version `V`, the canonical release among the events **by the publisher
the client trusts for `G`** that carry `["j","G:A"]` and `["v","V"]` is the one with the earliest
`created_at`, ties broken by the lexicographically lowest `id`. A later event never replaces a
version; publishers MUST NOT publish a second release of a version they already released, and
clients MUST keep using the files they first resolved (see *Lock file*), because `created_at`
is chosen by the signer.

[NIP-09](09.md) deletions are ignored for releases: a build that resolved a version yesterday
must resolve the same bytes tomorrow. A bad release is superseded by publishing a new version.

## Snapshot (kind `33071`)

Same tags as a release, plus `["d", "<groupId>:<artifactId>:<version>"]` with the root module's
coordinate. The version ends in `-SNAPSHOT`. As an addressable event the newest one replaces
older ones; clients pick the newest `created_at` (ties: lowest `id`). File names in the manifest
are Maven's timestamped ones, from which clients generate the version-level
`maven-metadata.xml`.

## Querying

| Need | Filter |
|---|---|
| A version | `{"kinds":[3071], "authors":[<pinned>], "#j":["G:A"], "#v":["V"]}` |
| All versions (for `maven-metadata.xml`) | `{"kinds":[3071,33071], "authors":[<pinned>], "#j":["G:A"]}` |
| A snapshot | `{"kinds":[33071], "authors":[<pinned>], "#j":["G:A"], "#v":["V-SNAPSHOT"]}` |
| Who signed a file | `{"kinds":[3071,33071], "#x":["<sha256>"]}` |
| Where blobs are | `{"kinds":[10063], "authors":[<pinned>]}` |

Clients query several relays and merge the results; a relay can only withhold events, never
forge them, and withholding is caught by the lock file.

## Why these tag letters

Single-letter tags are what relays index, and the obvious letters are taken: `a` (addresses),
`g` (geohash), `m` (MIME type), `d` (addressable identifier), `e`/`p`/`q`, `t`, `r`, `i`/`k`
(NIP-73), `l`/`L` (labels), `x` (NIP-94/Blossom hashes), and most others have a meaning in some
NIP or deployed client.

- **`j`** has no meaning in any NIP or in Quartz (Amethyst's protocol library, which implements
  most of them). Mnemonic: *jar*. It carries `groupId:artifactId` rather than splitting group
  and artifact into two letters, because every query needs both, and one indexed value per
  module keeps a Kotlin Multiplatform release (a dozen modules) to a dozen tags.
- **`v`** is the version. The only use we found (Marmot's MIP-05) also means "version", on its
  own kinds, so nothing conflicts.
- **`x`** keeps its Blossom/NIP-94 meaning, a sha256, so generic "who published this hash"
  tooling works unchanged.
- The manifest pointer is a multi-letter tag (`manifest`) because it needs no index of its own
  (its hash is also in an `x`), and it carries the size.

Readable `group`/`artifact`/`version` tags were considered and dropped: they duplicate `j`/`v`.

## Blossom

Publishers upload with [BUD-02](https://github.com/hzrd149/blossom/blob/master/buds/02.md)
using one kind `24242` authorization whose `x` tags cover every blob of the release (and
`server` tags scoping it to the target servers), falling back to one authorization per blob
for servers that refuse multi-hash tokens. Before signing anything they SHOULD check every blob
with [BUD-06](https://github.com/hzrd149/blossom/blob/master/buds/06.md) (`HEAD /upload`) and
stop if any blob fits on none of their servers. A release then takes two signatures: the upload
authorization and the release.

Clients fetch blobs (`GET /<sha256>`) from the release's `server` hints, then the publisher's
current kind `10063`, then their own fallbacks, and MUST check every blob's sha256.

## Trust

There is no registry. Each group (and its subgroups) is trusted to exactly one key, and a groupId
is a reversed domain, so by default the domain vouches for it through [NIP-05](05.md), the way
Maven Central ties a namespace to a domain:

1. Reverse the groupId into a host: `com.vitorpamplona.quartz` → `quartz.vitorpamplona.com`.
2. Walk from that host down to its registrable domain (per the public suffix list), looking up
   the name `releases` on each: `releases@quartz.vitorpamplona.com`, then
   `releases@vitorpamplona.com`.
3. The first key listed is the group's release key. Only a definite "none" (the domain doesn't
   exist, `nostr.json` is 404, or doesn't list `releases`) moves on to the next domain; a domain
   that can't be read stops the walk, so an outage never hands a group to a parent domain's key.

`releases` rather than `_`, because `_` is usually the domain's general identity, and release
keys are better kept apart (in a bunker that never posts). Clients MAY let users pin another
identity on the same domains, or a key directly (the only option for a group that isn't a
domain, such as one with `_` in a segment). Events by any other key are ignored.

Publishers SHOULD check before signing that their key is the one their group's domain names.

## Lock file

Clients SHOULD keep, per project, the key each group's domain vouched for and the manifest hash
each resolved version came from, and SHOULD fail (not silently switch) when the domain later
names another key or when no release of a locked manifest can be found.

Pinning the manifest rather than the event id means that after a key rotation (the publisher
changes `releases@` to a new key and re-signs the same manifests) locked builds keep resolving
the same files, once their users accept the new key.

## Related

Kind `30063` (release artifact sets, used by app stores) groups files for a release but is
addressable, so it can be replaced; Maven coordinates need immutable releases with an
earliest-wins rule, hence a regular kind.

## Why no yank

An earlier draft had a yank kind to deprecate a release without removing it. It was dropped: a
yanked version still appears in `maven-metadata.xml`'s version list, which is what Gradle's
dynamic versions (`1.+`) read, so it only produced warnings; a bad release is fixed by publishing
a new version, as on Maven Central; and a leaked key could yank genuine releases. A deprecation
kind can be added later without breaking clients, which ignore kinds they don't know.
