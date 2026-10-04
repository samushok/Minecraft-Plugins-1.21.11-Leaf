# Spheres 1.3.1 — Leaf 1.21.11 / Java 21

Unified plugin containing **SUMMER**, **SHULLER**, **SANTA** and **STORM** in one JAR.

## Install

1. Stop the server.
2. Remove the old separate `SummerBall` and `Santaball` JARs from `plugins/`.
3. Put `Spheres-1.3.1-Leaf-1.21.11.jar` in `plugins/`.
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

`build/Spheres-1.3.1-Leaf-1.21.11.jar`

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

Flow:
1. configurable wind-up vortex;
2. nearby valid targets are launched upward;
3. short configurable air hold;
4. targets are slammed down rapidly;
5. impact particles/lightning and optional configurable slam damage.

The owner is excluded from their own STORM. Creative/Spectator players are skipped.

### Modular item properties

`storm.item.properties` is data-driven. Supported modules in the editor:
- movement speed;
- attack speed;
- attack damage;
- armor;
- max health;
- knockback resistance.

`%bonuses%` inside `storm.item.description` expands to only the currently active modules. Removing Damage from the GUI removes both the attribute and its lore line. Adding Speed does the reverse.

### /stormconfig

The in-game editor supports:
- add property;
- remove property with Q;
- left/right click value changes;
- Shift+left/right lore ordering;
- ability values (radius, launch, hold, slam, damage, cooldown, particles);
- enable/disable slam damage;
- item preview.

Changes are saved immediately and currently online STORM items are refreshed from the new config.


### STORM 1.1.1 visual polish

The STORM visual is now staged:
- two counter-rotating storm-front rings;
- vertical tornado/eye column above the owner;
- optional dark eye core;
- optional per-target storm markers;
- configurable vertical launch trails;
- slam warning ring;
- two-stage expanding impact shockwave;
- cosmetic lightning flash.

Every major visual layer can be toggled independently under `storm.visual`.

### STORM texture input

`storm.item.texture` accepts any of:
- ready-made base64 texture value;
- full `https://textures.minecraft.net/texture/...` URL;
- only the texture hash.

The plugin converts URL/hash input to the required profile texture value automatically.


## STORM 1.3.0 — Pure Black Hole

STORM no longer uses launch/slam or player orbit physics.

The default ability is now a pure suction Black Hole:
- a filled circular event horizon built from concentric Black/Obsidian BlockDisplay rings;
- dense near-black particle core;
- accretion rings in the same event-horizon plane;
- 52 configurable particle streams that visibly move from outside toward the center;
- horizontal-only player suction;
- no outward Gravity Pulse;
- no final eject;
- event-horizon capture damping prevents victims from bouncing through the center;
- Time Fracture preserves player Y so it cannot recreate the old launch/slam feeling;
- Reality Fractures are visual-only and disabled by default.

The default event horizon is created around player-body height so captured players visually enter the black disk rather than being pulled below a high floating effect.

Key config groups:
- `storm.black-hole.gravity` — pure X/Z suction;
- `storm.black-hole.visuals.event-horizon-blocks` — black BlockDisplay disk;
- `storm.black-hole.visuals.accretion` — surrounding rings;
- `storm.black-hole.visuals.infall-streams` — inward particle flow;
- `storm.black-hole.time-fracture` and `temporal-echo` — unique internal distortion mechanics.


### STORM 1.3.1 cinematic polish

Pure Black Hole physics from 1.3.0 is unchanged.

Visual additions:
- formation phase: event-horizon blocks converge from outside before settling into the black disk;
- Black Hole visual scale grows from a tiny singularity to full size;
- multi-layer gravitational lensing halo around the event horizon;
- inward particle streams transition from cyan outside to violet near the core;
- final collapse uses a lensing snap + optional SONIC_BOOM particle with no physical knockback;
- center-fill BlockDisplays follow the same smooth assembly path as the outer rings.

All new layers are configurable under `storm.black-hole.visuals.formation`,
`storm.black-hole.visuals.lensing-halo`, and color settings.
