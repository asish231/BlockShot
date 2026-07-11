package com.blockshot.world.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockshot.world.BlockMaterial;
import com.blockshot.world.BlockPos;
import com.blockshot.world.Chunk;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OsmWorldGeneratorTest {

    @TempDir
    Path cacheDirectory;

    @Test
    void givenRoadWaterAndPark_whenBlocksQueried_thenSurfaceMaterialsFollowOsmFeatures() {
        OsmWorldGenerator generator = generator(List.of(
                new OsmElement.OsmRoad(List.of(pos(20, 0), pos(30, 0)), 4.0f),
                new OsmElement.OsmWater(polygon(40, 0, 46, 6)),
                new OsmElement.OsmPark(polygon(50, 0, 56, 6))));
        int surface = OsmWorldGenerator.GROUND_HEIGHT - 1;

        assertEquals(BlockMaterial.ASPHALT, generator.getBlockAt(new BlockPos(25, surface, 1)));
        assertEquals(BlockMaterial.WATER, generator.getBlockAt(new BlockPos(43, surface, 3)));
        assertEquals(BlockMaterial.GRASS, generator.getBlockAt(new BlockPos(53, surface, 3)));
        assertEquals(BlockMaterial.DIRT, generator.getBlockAt(new BlockPos(60, surface, 3)));
    }

    @Test
    void givenBuildingFootprint_whenBlocksQueried_thenItIsHollowWithWindowsRoofAndLadder() {
        OsmElement.OsmBuilding building = new OsmElement.OsmBuilding(
                polygon(0, 0, 8, 8), 3, BlockMaterial.BRICK);
        OsmWorldGenerator generator = generator(List.of(building));
        int floor = OsmWorldGenerator.GROUND_HEIGHT;
        int roof = floor + 9;

        assertEquals(BlockMaterial.CONCRETE, generator.getBlockAt(new BlockPos(4, floor, 4)));
        assertNull(generator.getBlockAt(new BlockPos(4, floor + 1, 4)));
        assertEquals(BlockMaterial.GLASS, generator.getBlockAt(new BlockPos(0, floor + 1, 4)));
        assertEquals(BlockMaterial.BRICK, generator.getBlockAt(new BlockPos(0, floor + 1, 0)));
        assertEquals(BlockMaterial.BRICK, generator.getBlockAt(new BlockPos(0, floor + 2, 4)));
        assertEquals(BlockMaterial.LADDER, generator.getBlockAt(new BlockPos(1, floor + 1, 1)));
        assertEquals(BlockMaterial.CONCRETE, generator.getBlockAt(new BlockPos(4, roof, 4)));
        assertEquals(roof, generator.upperBoundAt(4, 4));
    }

    @Test
    void givenFeaturesAcrossChunks_whenIndexed_thenOnlyIntersectingChunksReportThem() {
        OsmWorldGenerator generator = generator(List.of(
                new OsmElement.OsmBuilding(polygon(-20, -4, -12, 4), 2, BlockMaterial.STEEL)));

        assertTrue(generator.hasBuildingsInChunk(-1, 0));
        assertTrue(generator.hasBuildingsInChunk(-2, 0));
        assertFalse(generator.hasBuildingsInChunk(2, 2));
    }

    @Test
    void givenLoadedOsmData_whenChunkGenerated_thenStoredBlocksMatchCoordinateQueries() {
        OsmWorldGenerator generator = generator(List.of(
                new OsmElement.OsmBuilding(polygon(2, 2, 10, 10), 2, BlockMaterial.CONCRETE)));

        Chunk chunk = generator.generateChunk(0, 0);

        assertFalse(chunk.blocks.isEmpty());
        chunk.blocks.forEach((position, material) -> assertEquals(material, generator.getBlockAt(position)));
    }

    @Test
    void givenUncachedSector_whenChunkLoaded_thenJsonIsParsedIndexedAndSharedWithinSector() {
        OsmCoordinate projection = new OsmCoordinate(0.0, 0.0);
        AtomicInteger requests = new AtomicInteger();
        OsmHttpClient client = new OsmHttpClient(cacheDirectory,
                URI.create("https://example.test/api/interpreter"), (uri, body) -> {
                    requests.incrementAndGet();
                    return CompletableFuture.completedFuture(new OsmHttpClient.HttpResult(
                            200, roadJson(projection)));
                });
        OsmWorldGenerator generator = new OsmWorldGenerator(projection, client);

        generator.loadChunk(0, 0).join();
        generator.loadChunk(31, 31).join();

        assertEquals(1L, generator.revision());
        assertEquals(1, requests.get(), "all 32x32 chunks in a sector must share one request");
        assertEquals(BlockMaterial.ASPHALT,
                generator.getBlockAt(new BlockPos(7, OsmWorldGenerator.GROUND_HEIGHT - 1, 8)));
    }

    private static OsmWorldGenerator generator(List<OsmElement> elements) {
        return new OsmWorldGenerator(new OsmCoordinate(0.0, 0.0), elements);
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos(x, 0, z);
    }

    private static List<BlockPos> polygon(int minX, int minZ, int maxX, int maxZ) {
        return List.of(pos(minX, minZ), pos(maxX, minZ), pos(maxX, maxZ), pos(minX, maxZ), pos(minX, minZ));
    }

    static String roadJson(OsmCoordinate projection) {
        return "{\"elements\":[" + nodeJson(projection, 1, 0, 8) + ","
                + nodeJson(projection, 2, 15, 8) + ","
                + "{\"type\":\"way\",\"id\":10,\"nodes\":[1,2],"
                + "\"tags\":{\"highway\":\"residential\"}}]}";
    }

    private static String nodeJson(OsmCoordinate projection, long id, int x, int z) {
        OsmCoordinate.GeoCoordinate coordinate = projection.toGeo(x, z);
        return String.format(Locale.ROOT,
                "{\"type\":\"node\",\"id\":%d,\"lat\":%.12f,\"lon\":%.12f}",
                id, coordinate.latitude(), coordinate.longitude());
    }
}