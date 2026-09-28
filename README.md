# Minecraft Plugins — Leaf 1.21.11

Workspace for the user's Minecraft artifact plugins targeting **Leaf/Paper 1.21.11** on **Java 21**.

## Current plugins

- **SummerBall 2.1** — contains SUMMER + SHULLER
- **Santaball 1.4** — SANTA artifact

## Repository layout

- `source/SummerBall/` — complete source project from `SummerBall-Leaf-1.21.11-source(1).zip`
- `source/Santaball/` — complete source project from `Santaball-1.4-Leaf-1.21.11-source(1).zip`
- `SummerBall/` — plugin.yml/config.yml extracted from the current reference JAR
- `Santaball/` — plugin.yml/config.yml/manifest extracted from the current reference JAR
- `docs/current-builds.md` — SHA-256 hashes and target server information
- `docs/class-inventory.md` — classes found in the current JARs
- `artifacts/README.md` — why binary conversation attachments are referenced by hashes instead of copied through the connector

## Source verification

The two user-provided source ZIPs contain **19 files total** (9 SummerBall + 10 Santaball).
Every file under `source/` was verified against the corresponding ZIP using its Git blob SHA:
**19/19 exact matches**.

The current JAR resources were also compared with the source projects. Their plugin/config resources match the supplied current builds.

The target environment is **Leaf 1.21.11 / Java 21**.
