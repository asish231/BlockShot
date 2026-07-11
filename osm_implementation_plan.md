# Implementation Plan: Dynamic OpenStreetMap (OSM) World Generation

This plan outlines the architecture, data structures, coordinate projections, and API integration steps to dynamically load real-world city maps from OpenStreetMap (OSM) into the voxel engine, caching them on disk, and rendering them as solid voxel structures.

---

## 📂 File Architecture

Create the following new files under `src/main/java/com/blockshot/world/osm/`:

1.  [`OsmCoordinate.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/osm/OsmCoordinate.java): Handles Web Mercator projection and voxel space mapping.
2.  [`OsmElement.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/osm/OsmElement.java): Data records for buildings, roads, parks, and water bodies.
3.  [`OsmHttpClient.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/osm/OsmHttpClient.java): Handles background HTTP calls to Overpass API and local disk JSON caching.
4.  [`OsmParser.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/osm/OsmParser.java): Parses Overpass JSON responses into memory structures.
5.  [`OsmWorldGenerator.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/osm/OsmWorldGenerator.java): Performs spatial queries and generates voxel blocks dynamically for chunks.

Modify the following existing files:
-   [`ChunkManager.java`](file:///Users/asishsharma/programming/Llm/src/main/java/com/blockshot/world/ChunkManager.java): Integrate the new OSM world generator behind a launch flag.

---

## 🗺️ 1. Coordinate Projection (GPS to Voxel Space)

To map real-world Latitude/Longitude coordinates to game block coordinates $(X, Z)$, use the **Web Mercator (EPSG:3857)** projection. This projects the spherical Earth onto a flat plane where 1 unit = 1 meter at the equator:

### Projection Formulas
Given Earth's radius $R = 6378137.0$ meters:
$$x_{mercator} = R \times \lambda$$
$$z_{mercator} = R \times \ln\left(\tan\left(\frac{\pi}{4} + \frac{\phi}{2}\right)\right)$$
*(where $\lambda$ is longitude and $\phi$ is latitude in radians).*

### Voxel Mapping
To place the player's chosen spawn point at voxel coordinates $(0, 0)$:
1.  Choose a reference GPS coordinate for the map center (e.g., Reykjavik: $\phi_0 = 64.1466$, $\lambda_0 = -21.9426$).
2.  Compute the reference Mercator offset $(x_0, z_0)$.
3.  For any GPS coordinate $(\phi, \lambda)$, compute $(x_{mercator}, z_{mercator})$ and map to voxel space:
    $$X_{voxel} = \text{round}(x_{mercator} - x_0)$$
    $$Z_{voxel} = \text{round}(z_{mercator} - z_0)$$

---

## 🌐 2. Overpass API & Disk Cache

To avoid hitting network rate-limits and lag, split the world into a grid of **Sectors** (e.g., $512\text{m} \times 512\text{m}$).

### Fetching Sector Data
When a player enters a sector, check if a cached file exists:
`src/main/resources/osm_cache/sector_X_Z.json`.

If it does not exist, spawn a background thread to fetch data from the Overpass API:
```http
POST https://overpass-api.de/api/interpreter
Content-Type: application/x-www-form-urlencoded
Body:
data=[out:json][timeout:25];
(
  way["highway"](south,west,north,east);
  way["building"](south,west,north,east);
  relation["building"](south,west,north,east);
  way["natural"="water"](south,west,north,east);
  way["leisure"="park"](south,west,north,east);
);
out body;
>;
out skel qt;
```
*(Compute the bounding box coordinates `south, west, north, east` by converting the sector boundaries back to Lat/Lon).*

---

## 📊 3. Data Structures & Parsing

Parse the Overpass JSON response into lightweight record structures:

```java
public record OsmBuilding(
    List<BlockPos> footprint, // Polygon vertices in voxel space
    int heightLevels,          // From "building:levels", defaults to 3
    BlockMaterial wallMaterial // Based on tags (concrete, steel, brick)
) {}

public record OsmRoad(
    List<BlockPos> points,     // Line strip vertices
    float widthMeters          // Road width based on highway type
) {}

public record OsmWater(
    List<BlockPos> polygon
) {}
```

### Fast Spatial Querying (Grid Index)
Do not iterate through all buildings for every block query. Store elements in a hash map grouped by **Chunk Coordinate key** ($cx, cz$):
`Map<Long, List<OsmBuilding>> chunkBuildings`
When a chunk generates, it only queries elements associated with its chunk coordinate.

---

## 🧱 4. Block Generation Rules

Implement these rules in `OsmWorldGenerator.java` to turn vector polygons into solid voxel blocks:

### 1. Road Building
*   Iterate through all roads in the chunk.
*   Draw an asphalt path (`BlockMaterial.ASPHALT`) along the road's polyline points. Use a simple line-sweeping algorithm to color all blocks within `widthMeters / 2.0` of the line segment at surface level.

### 2. Hollow Building Generation
For each building polygon in the chunk:
*   Determine the floor level (plaza height) and ceiling level ($plaza + levels \times 3$).
*   **Walls:** Draw solid pillars at the polygon vertices and outer edge lines using the `wallMaterial` (e.g., `BlockMaterial.CONCRETE` or `BlockMaterial.STEEL`).
*   **Floors & Ceilings:** Fill the interior polygon at the bottom with concrete, and at the top with roof materials.
*   **Windows:** For wall blocks where $y \pmod 3 == 1$ (except corners), place `BlockMaterial.GLASS` instead of the wall material.
*   **Interior Stairs:** Place climbable ladders (`BlockMaterial.LADDER`) in one corner extending from bottom to roof.

### 3. Water and Parks
*   For any coordinate falling inside a water polygon, replace the surface blocks with `BlockMaterial.WATER`.
*   For coordinates inside park boundaries, replace the surface with `BlockMaterial.GRASS`.

---

## 🎛️ 5. Integration in `ChunkManager`

Introduce a configuration flag and update block lookups:

```java
public class ChunkManager implements CollisionWorld {
    private final boolean useOsm = System.getProperty("world.mode", "procedural").equalsIgnoreCase("osm");
    private final OsmWorldGenerator osmGenerator = new OsmWorldGenerator();

    public BlockMaterial generatedBlockAt(BlockPos pos) {
        if (useOsm) {
            return osmGenerator.getBlockAt(pos);
        }
        // Fall back to original procedural city code
        ...
    }
}
```

This guarantees the existing generation logic remains untouched while unlocking a fully functional real-world voxel map!
