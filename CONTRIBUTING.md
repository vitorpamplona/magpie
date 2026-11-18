# Contributing

```bash
./gradlew test                                   # spec, testkit, core (incl. golden tests vs Maven Central)
./gradlew :gradle-plugin:functionalTest          # TestKit builds: publish and consume through the plugins
./gradlew :gradle-plugin:functionalTest -Pmagpie.kmpTargets=jvm,android,js,wasmJs,linuxX64
./gradlew :gradle-plugin:functionalTest -Pmagpie.gradleVersion=8.14.3
./gradlew spotlessApply
```

Every test is hermetic: relays, Blossom servers and bunkers run in memory on `127.0.0.1`.
`android` needs `ANDROID_HOME`; Apple targets need macOS (CI runs both).

| Path | |
|---|---|
| `spec/` | Pure Kotlin Multiplatform: kinds and tags (`MagpieSpec`), coordinates, Maven path parsing, manifests, events, winner selection, lock file |
| `bridge/` | Java interfaces between the Gradle plugin and the engine |
| `core/` | The engine: resolver (`resolve/`), publisher (`publish/`), engine entry point |
| `gradle-plugin/` | `com.vitorpamplona.magpie` (settings) and `com.vitorpamplona.magpie.publish` (project) |
| `testkit/` | In-memory relay, Blossom server and NIP-46 bunker; fixtures |
| `docs/` | NIP draft, architecture, open questions |

Releases: set `VERSION_NAME` in `gradle.properties`, merge, and tag the merge commit `v<version>`;
`release.yml` tests and publishes both plugins to the Gradle Plugin Portal.
