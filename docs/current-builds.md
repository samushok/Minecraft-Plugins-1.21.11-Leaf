# Current uploaded builds

Target server: Leaf 1.21.11
Required Java: Java 21

## Original JARs supplied in chat

- SummerBall-2.1-Leaf-1.21.11(1).jar
  - Contains SUMMER + SHULLER
  - SHA-256: ef8750fc70d044e796736da62af6cc1e6b9ee89d7960600cd13f54de54cbee2d
- Santaball-1.4-Leaf-1.21.11(2).jar
  - SHA-256: 2d999ce5ea83786ac54ec23d83e3f35f99409659147cd6ceab72c996d00a17b2
- server(1).jar
  - Leaf/Paper bootstrap for Minecraft 1.21.11 / Java 21
  - SHA-256: f0e72363ac5a00870bac1203573d66b537bb3814f97342844914ce4459cee333
  - Not committed because the server binary is not project source and is ~80 MB.

## Source ZIPs supplied in chat

- SummerBall-Leaf-1.21.11-source(1).zip
  - 9 files
  - SHA-256: 90d87b44fc1ad0baf13de8202bfec05bdc30a47ed2acd87e5a1e84a9080380ca
- Santaball-1.4-Leaf-1.21.11-source(1).zip
  - 10 files
  - SHA-256: 1feda2127b7967c5eba366ae142ccb80ef3e6d74d320fcebb7f2bc37bae9501d

The complete extracted source trees are committed under `source/`.
All 19 source files match the supplied ZIPs exactly by Git blob SHA.

## Verification correction

Santaball already contains:
`paperweight-mappings-namespace: mojang`

So the earlier concern that the namespace attribute was missing does not apply to the supplied Santaball 1.4 JAR.
