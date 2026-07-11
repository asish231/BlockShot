# BlockShot 3D — Open World

A first-person, block-world **open-world sandbox** written in Java 21, rendered on the GPU with
OpenGL through LWJGL 3. Roam an effectively infinite, procedurally generated world of towns,
forests, oceans and wildlife — with living crowds, police, drivable vehicles, guns, destructible
buildings and optional LAN multiplayer.

## Run

```bash
cd /Users/asishsharma/programming/Llm
mvn -o compile exec:exec
```

The launcher passes the macOS-required `-XstartOnFirstThread` JVM argument automatically.

### Multiplayer (optional, LAN)

Launch arguments are parsed by `GameLaunchOptions`:

```bash
# Host a game (default port 29555) and play in the same window
mvn -o compile exec:exec -Dexec.args="--host --name=Alex"

# Join a host on the LAN
mvn -o compile exec:exec -Dexec.args="--join=192.168.1.20:29555 --name=Sam"
```

Other players appear as moving figures and their block edits are replicated to you in real time.
If the network is unavailable the game falls back to offline play instead of crashing.

## Controls

| Input | On foot | In a vehicle |
|---|---|---|
| `WASD` | move / strafe | throttle + steer |
| `Space` | jump | ascend (helicopter) / climb (plane) |
| `Shift` | sprint | descend |
| Mouse | look around (cursor captured) | look around |
| `F` | enter the nearest vehicle | exit the vehicle |
| `B` | toggle **BUILD** / **COMBAT** mode | — |
| Left click | BUILD: break a block · COMBAT: fire (hold to keep firing) | — |
| Right click | BUILD: place the selected block | — |
| Mouse wheel / `1`–`9` | select the block material | — |
| `Q` | COMBAT: switch weapon (pistol / rifle / shotgun) | — |
| `R` | COMBAT: reload | — |
| `Esc` | exit | exit |

## What you'll find

- **Smooth GPU rendering** — every chunk is meshed **once** into a GPU vertex buffer with
  **hidden-face culling** (interior and buried faces are dropped) and drawn with **view-frustum
  culling**. This replaces the old per-block immediate-mode path that made the world stutter, so
  roaming stays fast even with a large view distance.
- **Infinite streaming world** — chunks generate as you approach and **unload** once distant, with a
  per-frame generation budget so streaming never hitches. Memory and frame time stay bounded.
- **Deterministic generation** — the same seed always rebuilds the same world, biomes and cities.
- **Biomes & nature** — plains, forests, rocky hills and oceans; trees, flowers, translucent
  **water** with **swimming fish**.
- **Cities & people** — procedurally placed on a lattice, with a road grid, destructible buildings
  (real doors, `GLASS` windows, floors and roofs in varied brick/concrete/steel), **residents who
  walk around**, and GTA-style **crowds of civilians and police**.
- **Vehicles** — drive **cars**, fly **helicopters** and **planes**, each with seating capacity and
  arcade physics (ground following, lift, climb).
- **Guns & wanted level** — hit-scan weapons with finite ammo and reloads. Attacking people is a
  crime: civilians flee, a **wanted level** rises and **police pursue** you; it decays over time.
- **Destruction & physics** — break a support and the structure above **collapses**; unsupported and
  diagonal blocks **fall**, and falling debris can **crush** people and the player based on mass and
  impact speed. Collapse work is bounded so it can never freeze the frame.
- **Inventory & building** — break blocks to **collect** them and place them back with a proper voxel
  raycast; your edits persist as you roam (and replicate in multiplayer).
- **HUD & minimap** — health, wanted stars, mode, weapon/ammo, selected-block count, vehicle status,
  GPU/draw-call stats, and a top-down minimap plotting biomes, cities and nearby people.

## Architecture

The game is split into focused, unit-tested modules under `src/main/java/com/blockshot`. The root
`GpuBlockShot` is a thin coordinator that owns the window, input and the render pass and wires the
modules together.

| Package | Responsibility |
|---|---|
| `world` | `Box`, `Chunk`, `BlockMaterial`, `BlockPos`, `TerrainGenerator` (seeded noise + biomes), `ChunkManager` (streaming, cities, collision, edits), `WorldEditStore`, `BlockInventory`, `VoxelRaycaster` |
| `entity` | `Player` (movement/gravity/jump/collision), `Villager`/`Fish` AI, `Npc` (civilian/police states), `Vehicle` (car/helicopter/plane), `MouseLookController`, `CollisionWorld`/`SurfaceProvider` |
| `render` | `ChunkMeshBuilder`/`ChunkMeshData` (hidden-face meshing), `ChunkRenderer` (per-chunk VBOs), `ViewFrustum` (culling), `EntityRenderer`, `HudRenderer`/`HudState` |
| `physics` | `StructuralSystem` (support/load/collapse) and `FallingBlockEntity` (bounded gravity + impact damage) |
| `combat` | `WeaponState`/`WeaponType` (ammo, cooldown, reload) |
| `game` | `CrimeSystem`/`CrimeType` (wanted level), `WorldInteractionSystem` (inventory + edits + collapse + debris), `GameLaunchOptions` |
| `net` | `NetworkMessage`/`NetworkMessageCodec` and `MultiplayerServer`/`MultiplayerClient` (dependency-free UDP LAN) |

`BlockShot.java` is a **legacy** pure-Java/Swing software renderer kept for reference only; the
active game is `GpuBlockShot`.

## Tests

```bash
mvn -o test
```

63 JUnit 5 tests run without needing a graphics context and cover deterministic terrain, chunk
load/unload and bounded memory, negative chunk coordinates, city plaza flattening, player physics,
villager/NPC behaviour, hidden-face meshing, frustum culling, the VBO renderer (via a fake backend),
structural collapse and falling debris, weapons, the crime system, world edits/inventory, mouse
look, and the multiplayer message codec and loopback relay.
