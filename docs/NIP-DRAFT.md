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
- A **yank** event (regular kind `3072`) deprecates a release without removing it.

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
clients MUST keep using the release they first resolved (see *Lock file*), because `created_at`
is chosen by the signer.

[NIP-09](09.md) deletions are ignored for releases: a build that resolved a version yesterday
must resolve the same bytes tomorrow. To deprecate a release, yank it.

## Snapshot (kind `33071`)

Same tags as a release, plus `["d", "<groupId>:<artifactId>:<version>"]` with the root module's
coordinate. The version ends in `-SNAPSHOT`. As an addressable event the newest one replaces
older ones; clients pick the newest `created_at` (ties: lowest `id`). File names in the manifest
are Maven's timestamped ones, from which clients generate the version-level
`maven-metadata.xml`.

## Yank (kind `3072`)

```json
{
  "kind": 3072,
  "content": "CVE-2026-0001: please upgrade to 1.0.1",
  "tags": [
    ["e", "<release event id>"],
    ["k", "3071"],
    ["j", "com.example:lib"],
    ["j", "com.example:lib-jvm"],
    ["v", "1.0.0"],
    ["alt", "Yanked com.example:lib:1.0.0: …"]
  ]
}
```

A yank counts only when signed by the release's author. Yanked releases keep resolving; clients
SHOULD warn when they serve one and SHOULD NOT pick it as `latest`/`release` in generated
metadata. The `j`/`v` tags let a client fetch yanks with the same query it uses for releases.

## Querying

| Need | Filter |
|---|---|
| A version | `{"kinds":[3071], "authors":[<pinned>], "#j":["G:A"], "#v":["V"]}` |
| All versions (for `maven-metadata.xml`) | `{"kinds":[3071,33071,3072], "authors":[<pinned>], "#j":["G:A"]}` |
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

There is no registry. A client pins each group (and its subgroups) to one publisher key, written
in the build (`npub`) or learned on first use from a [NIP-05](05.md) name and recorded in a lock
file. Events by any other key are ignored.

## Lock file

Clients SHOULD keep, per project, the publisher key learned for each NIP-05-pinned group and the
release event id each resolved version came from, and SHOULD fail (not silently switch) when a
NIP-05 name later points to another key or when a locked release can't be found.

## Related

Kind `30063` (release artifact sets, used by app stores) groups files for a release but is
addressable, so it can be replaced; Maven coordinates need immutable releases with an
earliest-wins rule, hence a regular kind.
