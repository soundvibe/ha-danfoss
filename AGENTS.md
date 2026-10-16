# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project layout

- `danfoss-addon/` — the Home Assistant add-on (Java 21, Maven, single module).
  - `config.yaml` — HA add-on manifest; **`version` here is the release version**.
  - `CHANGELOG.md` — one `## [x.y.z]` section per release, newest first.
  - `RELEASE.md` — full release process (read it before releasing).
  - `src/main/java/net/soundvibe/hasio/` — `Bootstrapper` (wiring, MQTT/HA loops), `danfoss/protocol` (Dominion protocol handlers), `danfoss/data` (`IconRoom`, `IconMaster` → HA state/MQTT entity mapping), `ha/model` (MQTT discovery records).

## Build & test

- **JDK 21 is required.** `pom.xml` compiles with `--enable-preview` (string templates, `STR."..."`); building with a newer JDK fails with `invalid source release 21 with --enable-preview`.
- Run tests with JDK 21 on `JAVA_HOME`, e.g.:
  `cd danfoss-addon && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test`
- Verify changes with `./mvnw -q compile` + `./mvnw test` before finishing.

## Release process (do all of these in the same commit)

1. Bump `version` in `danfoss-addon/config.yaml`.
2. Add a matching `## [x.y.z]` section at the top of `danfoss-addon/CHANGELOG.md`.
3. **Never** bump `pom.xml` `<version>` — it stays `0.0.1`.

The `Dockerfile` reads the version from `config.yaml` to download the release JAR,
so a `config.yaml`/changelog mismatch silently ships the previous release.
Releases are tagged `vX.Y.Z` on GitHub; CI builds and uploads the JAR asset.

## Code conventions

- Uses Java 21 preview string templates and unnamed variables (`_`) — keep the style.
- New sensor values: the Danfoss master *pushes* data points; parse them in the
  protocol handler, carry them through a data record, expose as a state attribute,
  and (for MQTT) publish a discovery entity — follow the `hasBattery()`/`hasFloorSensor()`
  pattern in `IconRoom` (only create optional entities for capabilities actually reported).
- Add/update tests in `src/test` (`IconRoomTest` style) for every behavior change.
