# Spheres 1.3.3 — Leaf 1.21.11 / Java 21

Unified plugin containing **SUMMER**, **SHULLER**, **SANTA** and **STORM** in one JAR.

## Install

1. Stop the server.
2. Remove the old separate `SummerBall` and `Santaball` JARs from `plugins/`.
3. Put `Spheres-1.3.3-Leaf-1.21.11.jar` in `plugins/`.
4. Start the server. The plugin creates `plugins/Spheres/config.yml`.

Do not run Spheres together with the old separate SummerBall or Santaball plugins.

## Commands

Sphere give commands and the STORM editor:

- `/summerball` — gives the command sender a new SUMMER sphere.
- `/shullerball` — gives the command sender a new SHULLER sphere.
- `/santaball` — gives the command sender a new SANTA sphere.
- `/stormball` — gives the command sender a new STORM sphere.
- `/stormconfig` — opens the STORM in-game property/ability editor.

The commands accept no arguments and must be run by a player.

Permissions:

- `summerball.give`
- `shullerball.give`
- `santaball.give`
- `stormball.give`
- `stormconfig.use`

All default to OP.

## Items and activation

All four spheres are Spheres items. Legacy item compatibility is intentionally not required.

New PDC keys use the `spheres` namespace:

- `spheres:summer_ball`
- `spheres:shuller_ball`
- `spheres:santa_ball`
- `spheres:storm_ball`

Abilities activate with one SHIFT press while the corresponding sphere is in the off hand.

## Configuration

There is one config:

`plugins/Spheres/config.yml`

Top-level sections:

- `summer`
- `shuller`
- `santa`
- `storm`

The original SummerBall and Santaball configuration values were preserved when the projects were unified.

## Santa cooldown migration

On first start, if `plugins/Spheres/cooldowns.properties` does not exist and
`plugins/Santaball/cooldowns.properties` does exist, valid unexpired Santa cooldowns are imported.

The old file is not deleted or modified. Future saves go to `plugins/Spheres/cooldowns.properties`.

## Build

Target: Leaf/Paper 1.21.11, Java 21.

Preferred reproducible build:

```
python3 build.py --server /path/to/versions/1.21.11/leaf-1.21.11.jar --libraries /path/to/libraries
```

Output:

`build/Spheres-1.3.3-Leaf-1.21.11.jar`

The JAR manifest contains `paperweight-mappings-namespace: mojang` because SANTA uses Mojang-mapped NMS for packet-only Santa visuals.

GitHub Actions also builds and smoke-boots Spheres against stable Leaf 1.21.11 build 179.


## SANTA avalanche

The SANTA ability now creates an area avalanche rather than a single-target visual:

- the nearest valid player starts the ability;
- up to 5 valid players within 12 blocks of that primary target are selected;
- each selected player gets an individual falling snowball;
- each snowball uses only 7 BlockDisplay entities for lower server cost;
- a shared SNOWFLAKE + WHITE_ASH + CLOUD storm creates a white fog/blizzard effect around selected players;
- overlapping impacts from the same avalanche cannot damage the same player more than once;
- the owner remains immune to their own avalanche;
- at most 2 SANTA abilities can be active simultaneously, even if an older config contains a larger number.

All main avalanche values are configurable under `santa.ability.avalanche`.


## STORM

STORM activates with one SHIFT press while the sphere is in the off hand.

The current ability is a pure Black Hole / Singularity. The old launch, tornado, slam and eject mechanics are not used.

### Pure suction physics

- players are pulled toward the center on X/Z;
- no orbit force is applied to players;
- no outward Gravity Pulse exists;
- no final eject exists;
- event-horizon capture damping prevents targets from overshooting the core;
- Time Fracture preserves current Y so it cannot recreate the old launch/slam behavior;
- formation gravity ramps from 15% to full pull instead of instantly snapping players.

### STORM 1.3.3 — volumetric Black Hole

The event horizon is now a true 3D visual instead of a flat portal-like disk.

Visual stack:
- volumetric near-black Fibonacci core that stays spherical from every camera angle;
- spherical photon shell around the event horizon;
- configurable tilted gravitational-lensing/accretion plane;
- six accretion rings;
- 52 three-dimensional inward streams distributed around the sphere;
- each stream spirals inward from outside toward the core;
- cyan -> violet infall color transition;
- formation scale grows from a small singularity to full size;
- final collapse uses a lensing snap + optional SONIC_BOOM particle with no physical knockback.

The old filled BlockDisplay disk remains available as an optional legacy layer, but it is disabled by default so it cannot flatten the silhouette.

Performance safeguards:
- visual refresh defaults to 10 Hz instead of 20 Hz;
- a per-refresh particle budget caps the effective cosmetic density without changing the saved density value;
- multiple active Black Holes share the visual budget using a square-root scaling rule;
- particle rendering is skipped when no player is within the configured render distance;
- Blindness refreshes periodically instead of being re-applied every server tick;
- existing 1.3.1 configs are migrated once so the old flat BlockDisplay layer cannot silently return.

Key config groups:
- `storm.black-hole.gravity` — pure X/Z suction;
- `storm.black-hole.visuals.photon-shell` — spherical luminous boundary;
- `storm.black-hole.visuals.disk-tilt-degrees` — accretion/lensing plane tilt;
- `storm.black-hole.visuals.accretion` — luminous surrounding disk;
- `storm.black-hole.visuals.infall-streams` — 3D inward spiral flow;
- `storm.black-hole.visuals.formation` — formation animation and gravity ramp;
- `storm.black-hole.time-fracture` and `temporal-echo` — temporal distortion mechanics;
- `storm.black-hole.collapse` — final implosion behavior.

### Modular item properties

`storm.item.properties` is data-driven. The in-game editor can enable, disable, reorder and tune passive STORM properties without hardcoding lore.

`%bonuses%` inside `storm.item.description` expands only active modules, and `%ability_details%` reflects the current Black Hole values.

### Commands

- `/stormball` — gives the STORM sphere;
- `/stormconfig` — opens the STORM property/config editor;
- `/stormstop` — stops active STORM Black Holes.

All major Black Hole timings, physics values, colors, particle layers and presentation settings are configurable under `storm.black-hole`.
