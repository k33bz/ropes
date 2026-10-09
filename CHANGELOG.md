# Changelog

## 0.3.1

Security and reliability review, the Minecraft 26.3 port, and CI with a real-server test. Ships
on every line: `0.3.1+26.3` (`main`), `0.3.1+26.2` (`26.2`, new branch) and `0.3.1+26.1.2` (`26.1`).

### Fixed (security review)

- **High: a corrupt rope store was wiped at boot.** `RopeStore` started empty when
  `config/ropes_store.json` couldn't be parsed, and `Ropes.onInitialize` then saved that empty
  store over the file. Every rope was forgotten, and every invisible endpoint bat stayed in the
  world with nothing tracking it. The unreadable file is now copied to
  `ropes_store.json.corrupt-<millis>` first (or the server refuses to start if even that fails),
  and the stray-entity cleanup below stands down for that run, so restoring the backup brings the
  ropes back. Saves go through `RopeFiles.writeAtomically`.
- **High: duplicate endpoint bats on every chunk reload.** A chunk's entities load after its
  blocks, off-thread. CHUNK_LOAD (and the boot-time and periodic sweeps) verified a segment at
  once, found no bat yet, and spawned a new bat and knot. The old ones loaded a moment later, so
  each visit to a rope added another invisible, persistent bat. The periodic sweep also read the
  posts of every rope in the world, force-loading their chunks every 10 seconds. Verification now
  waits until both posts' chunks have their entities loaded and ticking (`Roping.drainAwaiting`),
  and the sweep skips unloaded ropes.
- **Medium: free lead farm and rope griefing through the rope's own entities.** Shears on the
  invisible endpoint bat unleashed it and dropped a lead, and so did punching or right-clicking
  the knot it hangs from. The sweep then quietly re-attached the rope, ready to farm again, and
  anyone could detach anyone's rope. Endpoint bats and the knots of stored ropes can no longer be
  hit or interacted with (right-clicking a rope's knot with a Rope ties from its fence), and
  endpoints take no damage (`ALLOW_DAMAGE`).
- **Medium: `/rope tie` was free and worked at any distance** (permission 0). Any player could
  string ropes between anyone's fences anywhere in loaded chunks, and one player could fill the
  server-wide `maxSegments` cap for everyone. A survival player now needs both posts within
  `tieReachBlocks` (new knob, default 6) and pays one Rope from their inventory, as with the
  right-click path. Ops, creative players and the console are exempt.
- **Medium: `/rope give` handed out up to 64 free Ropes per use to anyone.** It is now op-only
  (permission 2).
- **Medium: a malformed store entry crashed the server, on every boot.** A segment with no
  `dim`, a missing post or a null `endpointUuid` threw a NullPointerException inside the server
  tick (the verify sweep, climbing, CHUNK_LOAD). Such entries are dropped at load with a warning
  (the file is backed up first), and endpoint UUIDs are parsed without throwing.
- **Low: a Rope worked as a vanilla lead**, so Lead + String → 2 Ropes doubled leads for the
  price of string. Ropes can no longer leash mobs.
- **Low: an unreadable `config/ropes.json` was overwritten with defaults**, and most knobs were
  never range-checked (a NaN or negative `knotScale`, climb rates past ladder speed, a negative
  `maxSegments`). The broken file is now kept as `ropes.json.corrupt-*`, and `sanitize()` clamps
  every knob (NaN falls back to the default; climb rates stay below the ladder's 2.35 b/s).
- **Low: `knotHeadTexture` was pasted unescaped into a console-permission `summon` command.**
  Only plain base64 (what texture values always are) is accepted now.
- **Low: a half-strung rope could finish in another dimension.** The pending first fence now
  remembers its dimension.
- Stray rope entities (endpoint bats and knot caps with no stored rope, such as the duplicates
  above or the leftovers of a rope cut while its far end was unloaded) are removed when they load.

Also from the deep review after 0.3.0:

- Fix `/rope` levitation removal that stole Levitation applied by other sources.
- Fix a duplicate `targetRate` argument in the climb-rate calc (`climbMaxRate` -> `climbVerticalRate`).
- Null-safe `RopeStore` dimension comparison (`Objects.equals`).
- `/rope tie` refuses duplicate ties and enforces `maxSegments`; `/rope cut` only cuts your own
  ropes (creative and the console excepted); the store is written atomically (#3).

### Minecraft 26.3 port

- `main` now targets **26.3** (loader 0.19.5, fabric-api 0.161.0+26.3); the old 26.2 code lives
  on the new **`26.2`** branch (0.161.0+26.2), and `26.1` moves to loader 0.19.5 with sanctuary
  (fabric-api 0.155.3+26.1.2). Identical code on all three; only `gradle.properties` differs.
- `Entity.setInvulnerable` became `setPermanentlyInvulnerable` in 26.3: endpoint bats are kept
  undamageable by an `ALLOW_DAMAGE` hook instead, the same code on every line.
- `BlockState.blocksMotion()` is gone in 26.3: the climb headroom check tests the collision shape.
- `fabric.mod.json` takes its Minecraft range from `minecraft_version` and the dev run dir comes
  from `dev_run_dir` (`run263` / `run262` / `run`), so neither is clobbered by a cross-branch port.

### CI and releases

- `build.yml`: build + unit tests, a test summary, a real Fabric server test
  (`scripts/server_test.py`, three boots, see the README), README badges per line on the orphan
  `badges` branch, and the jar as an artifact.
- `release.yml`: push `v<mod_version>+<minecraft_version>` on a line to publish its jar (plus
  `.sha256`) as a GitHub release with notes from this file, after the same build and server test.
- New unit tests: `RopeFilesTest`, `RopeChecksTest`, `RopesConfigTest`.

## 0.3.0

- Climb-session NDJSON log for climbing leaderboards (`climbLog`, `climbLogDir`).

## 0.2.0

- Rope climbing: steep ropes are climbable via hidden Levitation / Slow Falling, with an angle-scaled
  rate capped below ladder speed, a headroom gate and fall-damage reset while in contact.

## 0.1.0

- Ropes between fence posts, drawn by vanilla's leash renderer (fence knot → invisible endpoint
  bat), with an 11-block segment cap, knot-to-knot chaining, decorative knot caps and `/rope`.
