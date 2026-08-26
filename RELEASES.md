# Releases

This document describes the release flow. The mod version is **centrally
managed in `build.gradle`** — `mcmod.info`, `injectedTags`, and the built JAR all derive from it.
Git tags must always be consistent with that version.

> For agent-oriented instructions (local build, caches, checklist, troubleshooting) see `AGENTS.md`.

## Quick start

1. **Bump `build.gradle`**: set `version = "x.y.z"`, e.g. `0.1.1`.

   Also align the constant in `src/main/java/com/gregorio/journeymapsync/JourneyMapSync.java`:

   ```java
   public static final String VERSION = "0.1.1";
   ```

   (`mcmod.info` stays as `${version}` — it is expanded by `processResources`).

2. **Commit the bump**:
   ```sh
   git add build.gradle src/main/java/com/gregorio/journeymapsync/JourneyMapSync.java
   git commit -m "0.1.1: <what changed>"
   git push origin master
   ```

3. **Run the `Release` workflow** (`Actions` → `Release` → `Run workflow` on `master`).
   It reads the version from `build.gradle` via `./gradlew properties` (fallback `grep`), creates an annotated tag `vx.y.z`
   (e.g. `v0.1.1`), pushes it, and then **explicitly triggers** the `Build`
   workflow via `workflow_dispatch` (no secrets required — `GITHUB_TOKEN`
   *can* trigger `workflow_dispatch`, unlike `push` events).

4. The `Build` workflow builds the universal JAR and publishes a **single release** `v0.1.1` with two assets:
   - `JourneyMapSync-0.1.1.jar` (version-pinned JAR)
   - `JourneyMapSync.jar` (unversioned JAR powering the permanent direct URL `.../releases/latest/download/JourneyMapSync.jar`)

## Alternative: manual tag

If you prefer to create the tag yourself after step 1:

```sh
git tag v0.1.1
git push origin v0.1.1
```

This triggers `Build` identically via `on.push.tags`. **Never** use `--force` on a `v*` tag that
already exists remotely — the Release workflow guards against this and the
Build workflow's drift guard will fail the run.

## Version format

| Component | Source | Example |
|-----------|--------|---------|
| Version | `build.gradle` `version = "..."` | `0.1.0` |
| Java constant | `JourneyMapSync.java` `VERSION` | `0.1.0` (must match) |
| Git tag (frozen) | `v` + version | `v0.1.0` |
| JAR (pinned) | `JourneyMapSync-<version>.jar` | `JourneyMapSync-0.1.0.jar` |
| JAR (unversioned) | `JourneyMapSync.jar` | `JourneyMapSync.jar` |
| Injected | `build.gradle` `injectedTags.put("VERSION", project.version)` | — |

`build.gradle` `version` and the `v*` tag are the **same string**.

## Build-time safety

The `Build` workflow (triggered by the tag) contains a **drift guard** that
verifies the tag's version matches `build.gradle`'s version. It extracts the Gradle version robustly:

```bash
GRADLE_VER=$(./gradlew properties --no-daemon -q 2>/dev/null | awk -F': ' '/^version:/{print $2}' | tr -d '[:space:]')
[ -z "$GRADLE_VER" ] && GRADLE_VER=$(grep -E '^\s*version\s*=\s*"' build.gradle | sed -E 's/.*version\s*=\s*"([^"]+)".*/\1/' | tr -d '[:space:]')
TAG_VER="${GITHUB_REF#refs/tags/v}"
[ "$TAG_VER" = "$GRADLE_VER" ] || exit 1
```

If they diverge (e.g. a tag was created without bumping `build.gradle`), the build fails:

```
tag version 0.1.1 != build.gradle version 0.1.0; bump build.gradle before tagging
```

This prevents shipping artifacts whose embedded version disagrees with the tag.

Additional safety:
- `jobs.build.env.VERSION: "0.0.0"` + `steps.prepare.outputs.VERSION` (via `$GITHUB_ENV`/`$GITHUB_OUTPUT` in the `Prepare JARs for release` step) silence the `Context access might be invalid: VERSION` linter warning in VS Code. Do not remove them.

## Local build

```bash
git clone https://github.com/TheTinkerersHaven/journeymap-sync.git
cd journeymap-sync
chmod +x ./gradlew
# Requires JDK 17 toolchain for the Gradle daemon (mod runs on Java 8/17 via GTNH)
./gradlew clean build --no-daemon --stacktrace   # -> build/libs/JourneyMapSync-<version>.jar
./gradlew compileJava --no-daemon                # quick check
```

## Direct / Auto-Download URLs

GitHub automatically resolves the latest release via:

```bash
# Direct download (Server & Client)
curl -L -o mods/JourneyMapSync.jar https://github.com/TheTinkerersHaven/journeymap-sync/releases/latest/download/JourneyMapSync.jar
# Pinned version
curl -L -o mods/JourneyMapSync-0.1.0.jar https://github.com/TheTinkerersHaven/journeymap-sync/releases/download/v0.1.0/JourneyMapSync-0.1.0.jar
```

## Notes for maintainers

- This mod is **fully AI-generated** (see README disclaimer). Review diffs before merging.
- Keep `build.gradle` version and `JourneyMapSync.java VERSION` in sync on every bump — the drift guard only checks `build.gradle`, but logs and `@Mod(version=...)` will look stale otherwise.
- JourneyMap itself is **client-only** and crashes a dedicated server from its own `serverStartingEvent`; do not put `journeymap-*.jar` in the server's `mods/` — only `JourneyMapSync.jar` belongs there (it hosts the `jmsync` relay channel). The dev `runServer` task runs without JM for the same reason; `runClient` pulls the deobfuscated JM via `rfg.deobf(...)` without bundling it.
- `MapType` caveat: `MapType.biome` does not exist in JourneyMap 5.2.x (`Name` = day/night/underground/surface/topo); `renderChunk(day)` paints day+night+topo in one call, underground slices are separate.
- See `AGENTS.md` for the full checklist and troubleshooting table.
