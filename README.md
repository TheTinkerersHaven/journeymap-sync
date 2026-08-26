# JourneyMap Sync

> **⚠️ Disclaimer: this mod is fully AI-generated.**
>
> Every line of code, documentation, and build config was produced by an AI coding assistant from a plan/spec and reviewed in a dev environment before building. Expect rough edges, run it in a local test world first before taking it to your long-running GTNH save, and open issues/PRs for fixes. The single commit on `main` is the deliverable; no hidden post-processing.

Relays explored [JourneyMap 5.2.x](https://github.com/TeamJM/journeymap-legacy) chunk tiles between players **through the game server itself** — no external sync server. For Minecraft 1.7.10 / GTNH (Forge `10.13.4.1614`), sharing **day + night + topo + cave (underground slices 0–15)**. Install the *same* jar on the server (it acts as a dumb FML broadcast relay) and on every client that wants to share.

## Install

- **Server:** drop `JourneyMapSync-0.1.0.jar` into `mods/` (required — it hosts the `jmsync` relay channel; vanilla plugin channels are swallowed on 1.7.10 dedicated servers, so there is no serverless mode). Offline-mode servers work (`online-mode=false`).
- **Clients:** each player puts the same jar into `mods/` **and keeps their normal JourneyMap 5.2.x `unlimited` jar** installed. `fairplay` JM builds disable cave mapping — swap to the unlimited jar if cave slices don't show up. JM 6.0.x for 1.7.10 is not supported in this build (different packages, would need a second adapter).
- GTNH 2.8.x runs on Java 17; the jar targets Java 8 bytecode so it also loads in stock 1.7.10 dev environments.

## How it works

One jar, one FML `SimpleNetworkWrapper` channel `jmsync`. Every incoming `C→S` packet (Hello, DigestRequest/Digest/TilesRequest inside a Relay, Tile) is rebroadcast by the server to every other player. Tiles capture per-chunk surface stacks + cave floors (lit air-above-block pairs with block-light nibbles) + biomes from `Chunk` data; the whole tile body is DEFLATEd. Received tiles are re-injected via JM's own `ChunkRenderController.renderChunk(...)` path from a single background worker — the same threading model JM itself uses (`BaseMapTask`). History lives under `journeymapsync/<serverKey>/<dim>/r.<rx>.<rz>.bin` plus a `.idx` sidecar for last-applied tracking; missing chunks are recovered via a digest/replay catchup handshake when peers meet or a dimension changes.

## Usage

- Join a world/server with a friend. You should see `journeymapsync: relay detected - sharing map data` once. If the server doesn't have the mod, clients warn once after ~10 s: `journeymapsync: server has no relay (install the jar server-side)`.
- Maps fill progressively while you explore. Peers dial in after an exchange of digests (one `Digest` is ≤3 regions ≈ 24 KB; pacing 250 ms) and `TilesRequest` batches of 256 entries under a 4 tiles/s token bucket (live tiles preempt replay).

### Commands (client-side)

```
/jmsync status                          # relay, peers seen, tiles sent/received/stored/injected, outbound queue
/jmsync inject <chunkX> <chunkZ>        # dev: inject a distinctive synthetic tile at the sender's current dimension
/jms                                  # alias for /jmsync
```

### Config

`config/journeymapsync.cfg` (Forge `Configuration`):

```
B:enabled=true
I:sendRadiusChunks=8          # sweep radius around the player every second
I:maxTilesPerSecond=4         # outbound token bucket
B:verboseLogging=false
```

## Build

```bash
./gradlew build          # JDK 17 toolchain, output targets Java 8 bytecode
ls build/libs/JourneyMapSync-0.1.0.jar
```

The `rfg.deobf('maven.modrinth:journeymap:5.2.20')` dependency remaps JourneyMap to dev mappings for `runClient` without bundling it into the release jar; in production you install whichever JM jar you prefer alongside it.

## Verification status (2026-08-26 dev run)

- ✅ Dedicated-server boot with mod (5 mods, `jmsync` channel, `Done`).
- ✅ Client + JM 5.2.20 init (BlockMD cache, `tile injection active`).
- ✅ Relay handshake (`relay detected`, `relay available: true`).
- ✅ Live capture — 614 tiles sent + stored in one in-world session.
- ✅ Injection proof (pixel): synthetic tile at chunk `(-3,-13)` → `day/-1,-1.png` red/lime wool checkerboard; `1/-1,-1.png` glowstone floors y=20 (odd columns) and `2/-1,-1.png` y=40 (even columns) rendered through `CaveRenderer` with zero JM thread assertions.
- ✅ Headless harness — 20/20 PASS (codec round-trips, wire caps, store latest-wins/reload/compaction bookkeeping).
- ⏭️ Relay e2e reception + catchup replay + no-jar warning: harness-verified, not GUI-proven — cross-client reception requires two online players. Copy the same jar to your real instance and have a friend join before verifying those paths. See the `journeymap-sync-backport-plan.md` Verification section for the manual smoke steps.

## Caveats

- JourneyMap is **client-only**: the Modrinth 5.2.20 release jar crashes a dedicated server from its own `serverStartingEvent` (`AbstractMethodError`); do not put it in the server's `mods/` — the crash is in JM, not this mod. Only `JourneyMapSync` belongs there.
- 256-high worlds only; content above y=255 is truncated.
- Cave mapping needs JM's **unlimited** flavor.

## License

No license file yet — treat the repo as all-rights-reserved until one is added. If you need to redistribute a patched build, open an issue.
