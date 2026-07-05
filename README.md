# Ropes

Craftable **ropes strung between fence posts at any angle** for Fabric dedicated servers.
Entirely **server-side** — vanilla clients connect with no mods and no resource pack. The rope
you see is drawn by **vanilla's own leash renderer**, so there is nothing to install client-side.

**For Minecraft 26.2** (branch `main`) and **26.1.x** (branch `26.1`) · Fabric Loader ≥ 0.19.3 · Java 25

---

## How it works (the vanilla-leash mechanism)

A leash in 26.x is a **`leash` data component**, and a `leash_knot` on a fence is a leash
**holder**, not a leashable entity — so you cannot make a rope out of two static knots. The
working pattern (proven by the [spike](#the-spike)) is:

> a fence **leash-knot** (the holder) ↔ an **invisible, pinned, leashable mob** (the moving end).

Ropes spawns an invisible bat (`Invisible / NoGravity / Silent / NoAI / PersistenceRequired /
Invulnerable`) at one fence and leashes it to the other fence's knot. Vanilla then renders the
leash — the **rope** — between them, at **any angle** (diagonal and vertical both confirmed
server-side).

### The 11-block segment rule

Vanilla snaps a leash at **12 blocks**; **11 is the stable maximum**. So each rope *segment* is
capped at 11 blocks (`maxSpanBlocks`, clamped to 1–11). For longer runs, **chain**: string A→B,
then continue from B to the next fence — B's fence becomes the next segment's knot, so ropes
daisy-chain knot-to-knot across arbitrary distance.

### Decorative knot caps

At every tie-point of a segment, Ropes spawns small decorative `item_display` **knot caps** (at
fence A's knot, at fence B, and at the endpoint) so the connection reads as *deliberately tied*
rather than a rope vanishing into a post. Default cap is a small coil of lead (pure vanilla, no
external texture); set `knotHeadTexture` to a "rope knot" player-head texture value to theme it.
Disable with `showKnots: false`. The caps are tag-scoped to their segment and cleaned up together
with the rope on cut / fence-break / reload.

---

## Using it

### Craft a Rope

A **Rope** is a **lead marked with a data component** (`custom_data {ropes_item:1b}` + the name
"Rope") — a pure-vanilla item so any client can hold, craft, and see it. The datapack recipe
(shipped in the mod) is:

```
Lead + String  →  2 × Rope   (shapeless)
```

Or grab some with `/rope give [count]`.

### String a rope by hand

1. **Right-click a fence** with a Rope in hand → a knot is placed and a pending rope is armed.
2. **Right-click a second fence** within 11 blocks → the segment is created (one Rope consumed).
3. To go further, **right-click the next fence** to chain another segment from there.

Reject conditions show an actionbar message (same fence twice, not a fence, or > 11 blocks).

### Cut a rope

**Shears** on a rope's fence — **right-click or attack (left-click)** — cuts the nearest segment:
the endpoint entity and knot caps are removed, an orphaned knot is culled, and a Rope drops back.

### Break a fence

Breaking **either fence post** of a segment drops the Rope and cleans up the endpoint entity,
knot caps, and any orphaned knot (`PlayerBlockBreakEvents`).

---

## Commands (the interaction twins)

Sneak/right-click interactions can't be driven headlessly, so every interaction has a command
twin that runs the **same** logic (span validation, chaining, retry-until-confirmed attach,
save-on-every-mutation). Permission 0 by default (a command grants no power a Rope-in-hand
doesn't — gate via your permission mod if you want to restrict rope-building):

| Command | Effect |
|---|---|
| `/rope tie <ax ay az> <bx by bz>` | String a segment A→B (validates the 11-block span). |
| `/rope cut <x y z>` | Cut the rope nearest that block. |
| `/rope give [count]` | Give yourself Rope items. |
| `/rope list` | How many segments are stored. |

---

## Persistence & the JSON schema

Segments are stored in **`config/ropes_store.json`** (gson), **saved on every mutation** (add /
remove) — never deferred. On server start and on chunk load, every segment's leash is re-verified
and **re-linked** if an endpoint entity was lost (the spike showed reload survives; this is
defensive).

```jsonc
{
  "segments": [
    {
      "dim": "minecraft:overworld",
      "fenceA": [x, y, z],        // the knot-holding fence (leash HOLDER end)
      "fenceB": [x, y, z],        // the endpoint-mob fence (leash MOVING end)
      "endpointUuid": "…",        // the invisible bat leashed to fenceA's knot
      "owner": "…"                // uuid of the player who strung it (audit; may be null)
    }
  ]
}
```

Knobs live in a separate **`config/ropes.json`**:

```jsonc
{
  "maxSpanBlocks": 11,        // per-segment ceiling (clamped 1–11; vanilla snaps at 12)
  "dropRopeOnBreak": true,    // drop a Rope back when a segment is cut / a fence breaks
  "verifyIntervalTicks": 200, // periodic re-verification sweep (0 = boot/chunk-load only)
  "showKnots": true,          // decorative knot caps at tie-points
  "knotScale": 0.35,          // knot-cap item_display scale
  "knotHeadTexture": "",      // optional player-head texture for a themed knot cap
  "climbEnabled": true,       // rope climbing (see Climbing section for the rest)
  "climbMinAngleDeg": 75.0, "climbReach": 0.6, "climbLookDeg": 30.0,
  "climbFloorRate": 0.4, "climbVerticalRate": 0.9, "climbMaxRate": 1.8,
  "climbResetFallWhileTouching": true
}
```

---

## ⚠️ Open items — verify on first deploy

The [spike](#the-spike) proved the leash attachment is real **server-side** (RCON
`data get entity … leash` resolves to the fence knot) at any angle, and that it survives reload.
The v0.2.0 climb tests prove the mechanism and rate model server-side. A few things are
**client-side feel/render** that only a real vanilla client can confirm:

> 1. **Does a vanilla 26.x client visibly render the rope** between the fence knot and the
>    synthetic invisible endpoint mob (diagonal + vertical), continuous across chained segments?
>    (Same leash mechanism vanilla draws for every leashed animal — almost certainly yes.)
> 2. **Climb feel/render**: bots measure server-applied rates and effects, but mineflayer (through
>    the ViaProxy 1.21↔26.x bridge) does not faithfully reproduce effect-driven vertical movement,
>    so the *client-visible* climb — the ascend/descend feel, the free-fall-on-release damage, and
>    stopping cleanly at a ledge top — is a **human playtest item**. Server-side these are proven:
>    the mod applies hidden Levitation/Slow-Falling on contact, the headroom gate stops the rise
>    below a ceiling with zero suffocation damage, below-gate ropes don't climb, and fall distance
>    is reset only while climbing.
> 3. Decorative **knot caps** spawn + are tagged server-side; their on-screen look is part of the
>    same eyeball check.

---

## Climbing (v0.2.0)

Steep ropes are **climbable**. Climbing is driven entirely by **hidden status effects** (no
velocity rubber-banding), computed once per tick per player from the rope registry — no entity
scanning.

**Being in contact:** within `climbReach` (default 0.6 blocks) of a segment whose slope is at
least `climbMinAngleDeg` (default 75°). Ropes flatter than the gate are **aesthetic only** — you
can stand on or clip them, but not climb (`k33bz: "clipping is fine"`).

**Look / sneak → action** (a treading-water model, no hover):

| Input | Action | Effect |
|---|---|---|
| Look **up** (pitch < −`climbLookDeg`, default 30°) | **Ascend** | hidden Levitation |
| Look **level or down** | **Descend** | hidden Slow Falling drift |
| Hold **sneak** | **Release** | nothing applied; a normal fall (with damage) begins |

Effects are applied **hidden** — `showParticles=false, showIcon=false` — so there are no
levitation sparkles or HUD icon; it looks like natural climbing. Leaving contact actively removes
the Levitation, so you **stop at a ledge** instead of rocketing past the top.

**Rate scales with angle** off a fixed floor: gentle at the gate, faster toward vertical, hard-capped
below the vanilla ladder (~2.35 b/s). See the rate-mechanism note below.

**Headroom suffocation gate** (always on, not configurable): before an ascend tick, if the block
you'd rise into is solid, the lift is skipped that tick — you're never forced up into a ceiling.

**Fall damage:** while climbing (in contact, not sneaking) your fall distance is reset every tick,
so climbing is damage-free. Sneak-release or leaving contact stops the reset — a fall from the
contact point deals normal damage.

### Config (climb knobs, in `config/ropes.json`)

```jsonc
{
  "climbEnabled": true,
  "climbMinAngleDeg": 75.0,      // gate: flatter ropes are aesthetic-only
  "climbReach": 0.6,             // contact distance to the segment line
  "climbLookDeg": 30.0,          // look-up past this to ascend; else descend
  "climbFloorRate": 0.4,         // curve floor at the gate (see rate note)
  "climbVerticalRate": 0.9,      // curve mid at vertical (config)
  "climbMaxRate": 1.8,           // hard cap (< ladder 2.35)
  "climbResetFallWhileTouching": true
}
```

### The rate mechanism (measured, v0.2.0)

Levitation amplitude is an integer effect; measured server-authoritative rates on 26.1.2 (applied
continuously every tick, which is how the mod applies it) are **amp0 = 0.891 b/s** and
**amp1 = 1.806 b/s** — both safely below the ladder's ~2.35 b/s.

A sub-amp0 target (like the 0.4 config floor) would require a duty cycle that **removes** levitation
on off-ticks, and gravity on those ticks makes the low net rate jittery/bouncy. Per the design's
explicit fallback, the delivered climb therefore **floors at continuous amp0 (~0.9 b/s)** — "usable,
not a crawl" — and **scales up toward the cap** by duty-blending between amp0 and amp1 as the rope
steepens (steeper = faster). The `climbFloorRate`/`climbVerticalRate` config values are retained but
the effective floor is clamped up to amp0; this tradeoff is documented in `ClimbRate`.

### Forward-compat / internals: the rope registry

`RopeRegistry` exposes each segment's geometry as cheap pure functions read every tick with no
entity-scanning: `geometryOf` (slope / `pitch()` / `isNearVertical`), `distanceToSegment` (clamped
point-to-segment), and `nearestClimbable`. The climb detector runs entirely off these stored
endpoints. Endpoint mobs are also **no-collision** (`collisionRule=never` team) and **no-physics**,
so a climbing player at an endpoint can't shove the anchor and stretch/snap the rope.

## The spike

The mechanism, the 11-block ceiling, the reload survival, and the knot-to-knot-fails finding were
all de-risked in a dedicated spike on MC 26.1.2 before this mod was written — see
`reports/ropes-leash-spike.md` in the mc-test-harness repo (`ropes-spike` branch).

## Building

```
./gradlew build      # requires JDK 25
```

Branch `main` targets 26.2; branch `26.1` is identical code with 26.1.2 dependency pins. CI builds
both on every push.

## License

MIT.
