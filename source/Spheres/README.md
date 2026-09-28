# Spheres 1.0 — Leaf 1.21.11 / Java 21

Unified plugin containing **SUMMER**, **SHULLER** and **SANTA** in one JAR.

## Install

1. Stop the server.
2. Remove the old separate `SummerBall` and `Santaball` JARs from `plugins/`.
3. Put `Spheres-1.0-Leaf-1.21.11.jar` in `plugins/`.
4. Start the server. The plugin creates `plugins/Spheres/config.yml`.

Do not run Spheres together with the old separate SummerBall or Santaball plugins.

## Commands

Exactly three self-give commands are registered:

- `/summerball` — gives the command sender a new SUMMER sphere.
- `/shullerball` — gives the command sender a new SHULLER sphere.
- `/santaball` — gives the command sender a new SANTA sphere.

The commands accept no arguments and must be run by a player.

Permissions:

- `summerball.give`
- `shullerball.give`
- `santaball.give`

All default to OP.

## Items and activation

All three spheres are new Spheres items. Legacy item compatibility is intentionally not required.

New PDC keys use the `spheres` namespace:

- `spheres:summer_ball`
- `spheres:shuller_ball`
- `spheres:santa_ball`

Abilities activate with one SHIFT press while the corresponding sphere is in the off hand.

## Configuration

There is one config:

`plugins/Spheres/config.yml`

Top-level sections:

- `summer`
- `shuller`
- `santa`

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

`build/Spheres-1.0-Leaf-1.21.11.jar`

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
