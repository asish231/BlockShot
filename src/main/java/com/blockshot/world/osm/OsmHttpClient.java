package com.blockshot.world.osm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Asynchronously fetches 512-metre Overpass sectors and caches their JSON on disk. */
public final class OsmHttpClient {

    public static final int SECTOR_SIZE_METERS = 512;
    public static final URI DEFAULT_ENDPOINT = URI.create("https://overpass-api.de/api/interpreter");
    public static final Path DEFAULT_CACHE_DIRECTORY = Path.of("src/main/resources/osm_cache");

    private final Path cacheDirectory;
    private final URI endpoint;
    private final Transport transport;
    private final ConcurrentMap<Sector, CompletableFuture<String>> requests = new ConcurrentHashMap<>();

    public OsmHttpClient() {
        this(Path.of(System.getProperty("world.osm.cache", DEFAULT_CACHE_DIRECTORY.toString())),
                URI.create(System.getProperty("world.osm.endpoint", DEFAULT_ENDPOINT.toString())),
                defaultTransport());
    }

    public OsmHttpClient(Path cacheDirectory, URI endpoint, Transport transport) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.transport = Objects.requireNonNull(transport, "transport");
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Overpass endpoint must use HTTP or HTTPS");
        }
    }

    /** Returns cached JSON or starts one shared background request for this sector. */
    public CompletableFuture<String> fetchSector(int sectorX, int sectorZ,
                                                  OsmCoordinate projection) {
        Objects.requireNonNull(projection, "projection");
        Sector sector = new Sector(sectorX, sectorZ);
        CompletableFuture<String> result = requests.computeIfAbsent(sector,
                ignored -> loadSector(sector, projection));
        result.whenComplete((json, failure) -> {
            if (failure != null) requests.remove(sector, result);
        });
        return result;
    }

    public Path cacheFile(int sectorX, int sectorZ) {
        return cacheDirectory.resolve("sector_" + sectorX + "_" + sectorZ + ".json");
    }

    private CompletableFuture<String> loadSector(Sector sector, OsmCoordinate projection) {
        Path cacheFile = cacheFile(sector.x(), sector.z());
        return CompletableFuture.supplyAsync(() -> readCached(cacheFile))
                .thenCompose(cached -> cached != null
                        ? CompletableFuture.completedFuture(cached)
                        : requestSector(sector, projection, cacheFile));
    }

    private String readCached(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) return null;
        try {
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CompletionException("Could not read OSM cache " + cacheFile, exception);
        }
    }

    private CompletableFuture<String> requestSector(Sector sector, OsmCoordinate projection,
                                                     Path cacheFile) {
        int minimumX;
        int minimumZ;
        try {
            minimumX = Math.multiplyExact(sector.x(), SECTOR_SIZE_METERS);
            minimumZ = Math.multiplyExact(sector.z(), SECTOR_SIZE_METERS);
        } catch (ArithmeticException exception) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("OSM sector is outside voxel range", exception));
        }
        OsmCoordinate.GeoBounds bounds = projection.boundsForVoxelSquare(
                minimumX, minimumZ, SECTOR_SIZE_METERS);
        String formBody = "data=" + URLEncoder.encode(overpassQuery(bounds), StandardCharsets.UTF_8);
        return transport.post(endpoint, formBody).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CompletionException(new IOException(
                        "Overpass returned HTTP " + response.statusCode()));
            }
            writeCached(cacheFile, response.body());
            return response.body();
        });
    }

    private static String overpassQuery(OsmCoordinate.GeoBounds bounds) {
        String box = String.format(Locale.ROOT, "%.8f,%.8f,%.8f,%.8f",
                bounds.south(), bounds.west(), bounds.north(), bounds.east());
        return "[out:json][timeout:25];\n"
                + "(\n"
                + "  way[\"highway\"](" + box + ");\n"
                + "  way[\"building\"](" + box + ");\n"
                + "  relation[\"building\"](" + box + ");\n"
                + "  way[\"natural\"=\"water\"](" + box + ");\n"
                + "  way[\"leisure\"=\"park\"](" + box + ");\n"
                + ");\n"
                + "out body;\n"
                + ">;\n"
                + "out skel qt;";
    }

    private void writeCached(Path cacheFile, String json) {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, cacheFile.getFileName().toString(), ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new CompletionException("Could not write OSM cache " + cacheFile, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The completed cache remains usable; a stale temporary file is harmless.
                }
            }
        }
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (endpoint, formBody) -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "BlockShot/1.0 OSM voxel loader")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> new HttpResult(response.statusCode(), response.body()));
        };
    }

    @FunctionalInterface
    public interface Transport {
        CompletableFuture<HttpResult> post(URI endpoint, String formBody);
    }

    public record HttpResult(int statusCode, String body) {
        public HttpResult {
            Objects.requireNonNull(body, "body");
        }
    }

    private record Sector(int x, int z) {
    }
}