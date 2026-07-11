# BlockShot 3D — Open World

A first-person, block-world **open-world sandbox** written in Java 21, rendered on the GPU with
OpenGL through LWJGL 3. Roam an effectively infinite, procedurally generated world of towns,
forests, oceans and wildlife — with living crowds, police, drivable vehicles, guns, destructible
buildings and optional LAN multiplayer.

![BlockShot 3D Gameplay](image.png)

## Run

```bash
cd /Users/asishsharma/programming/Llm
mvn -o compile exec:exec

# Explore a different world — any number is a valid seed (default 1337)
mvn -o compile exec:exec -Dexec.args="--seed=20260711"

# Stream real streets and buildings around Reykjavik from OpenStreetMap
mvn -o -Dworld.mode=osm compile exec:exec

# Choose another GPS point to place at voxel X=0, Z=0 (example: central London)
mvn -o -Dworld.mode=osm -Dworld.osm.latitude=51.5074 -Dworld.osm.longitude=-0.1278 compile exec:exec
```

The launcher passes the macOS-required `-XstartOnFirstThread` JVM argument automatically.
In OSM mode, 512 m sectors load in the background from the Overpass API and are cached under
`src/main/resources/osm_cache/sector_X_Z.json`. Cached sectors are reused on later runs, and a
temporary network failure leaves the already generated ground playable rather than blocking the
render loop. Overpass requests are serialized and transient rate limits are retried with bounded
backoff. The endpoint and cache location can be overridden with `-Dworld.osm.endpoint=...` and
`-Dworld.osm.cache=...`.

### If the view or player won't move

The window now **brings itself to the front and grabs the mouse on launch**, so on-foot movement
(`WASD`) and mouse-look work immediately — there is intentionally **no visible cursor** while playing
(you slide the mouse/trackpad to turn). If the window ever loses focus (for example you Cmd-Tab away),
the real cursor is released so other apps stay usable and the HUD shows **CLICK WINDOW TO CAPTURE
MOUSE** — click once inside the window to grab it again. Pressing **F11** for borderless fullscreen
also reliably re-grabs focus, so it doubles as an "unstick" button. Always launch with
`mvn -o compile exec:exec` (not a bare `java -jar`), because on macOS the `-XstartOnFirstThread`
argument it adds is required or the window freezes with no input at all.

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
When playing together, pass the **same `--seed`** on every machine so everyone roams the same world
(leaving it out works too — everyone then gets the shared default seed).

## Controls

| Input | On foot | In a vehicle |
|---|---|---|
| `WASD` | move / strafe | throttle + steer |
| `Space` | jump | ascend (helicopter) / climb (plane) |
| `Shift` | sprint | descend |
| Mouse | look around (mouse grabbed; **click** to re-grab after losing focus) | look around |
| `F` | enter the nearest vehicle | exit the vehicle |
| `B` | toggle **BUILD** / **COMBAT** mode | — |
| Left click | BUILD: break a block · COMBAT: fire (hold to keep firing) | — |
| Right click | BUILD: place the selected block | — |
| Mouse wheel / `1`–`9` | select the block material | — |
| `Q` | COMBAT: switch weapon (pistol / rifle / shotgun) | — |
| `R` | COMBAT: reload | — |
| `F11` | toggle **borderless fullscreen** (also re-grabs focus) | toggle fullscreen |
| `Esc` | exit | exit |

## What you'll find

- **Smooth GPU rendering** — every chunk is meshed **once** into a GPU vertex buffer with
  **hidden-face culling** (interior and buried faces are dropped) and drawn with **view-frustum
  culling**. This replaces the old per-block immediate-mode path that made the world stutter, so
  roaming stays fast even with a large view distance.
- **Infinite streaming world** — chunks generate as you approach and **unload** once distant, with a
  per-frame generation budget so streaming never hitches. Memory and frame time stay bounded.
- **Seeded, asymmetric world generation** — launch with `--seed=<number>` and the planner
  (`WorldLayout`) scatters cities of **different sizes** irregularly across coarse regions, links
  neighbouring cities with **winding countryside highways** that grade smoothly between city
  elevations and causeway across water, and sprinkles the countryside with **farmstead hamlets**
  (cottage, fenced yard, crops). The same seed always rebuilds the exact same world; a different
  seed gives a genuinely different map.
- **Optional real-world generation** — launch with `-Dworld.mode=osm` to project OSM roads,
  buildings, parks and water around a chosen latitude/longitude into metre-scaled voxels. Buildings
  are hollow and enterable, with material-aware walls, windows every floor, concrete floors/roofs
  and climbable ladders; roads retain widths inferred from their OSM highway type.
- **Biomes & nature** — plains, forests, rocky hills and oceans; trees, flowers, translucent
  **water** with **swimming fish**.
- **Cities & people** — every city is districted (residential, mall/hospital, courtyard,
  garden farm, beach) with a guaranteed **landmark** (twin towers or palace) at its centre,
  destructible buildings (real doors, `GLASS` windows, ladders, floors and roofs in varied
  brick/concrete/steel), **residents who walk around**, and GTA-style **crowds of civilians and
  police**.
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
| `world` | `Box`, `Chunk`, `BlockMaterial`, `BlockPos`, `TerrainGenerator` (seeded noise + biomes), `WorldLayout` (seeded city/road/hamlet planning), `ChunkManager` (streaming, procedural/OSM selection, collision, edits), `WorldEditStore`, `BlockInventory`, `VoxelRaycaster` |
| `world.osm` | Web Mercator projection, asynchronous Overpass client + sector cache, dependency-free JSON parsing, chunk spatial indexes and OSM-to-voxel generation |
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

101 JUnit 5 tests run without needing a graphics context and cover deterministic terrain, seeded
city/road/hamlet layout (reproducibility, asymmetry, road corridors between cities, landmark
guarantees, block-vs-query parity), chunk load/unload and bounded memory, negative chunk
coordinates, city plaza flattening, OSM projection/JSON parsing/sector caching/spatial indexing,
hollow real-world structures and asynchronous chunk refresh, launch-option parsing including `--seed`, player physics,
villager/NPC behaviour, hidden-face meshing, frustum culling, the VBO renderer (via a fake backend),
structural collapse and falling debris, weapons, the crime system, world edits/inventory, mouse
look, mouse-capture/focus handling, and the multiplayer message codec and loopback relay.
