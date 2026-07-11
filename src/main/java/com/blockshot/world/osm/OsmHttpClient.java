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
import java.util.concurrent.TimeUnit;

/** Asynchronously fetches 512-metre Overpass sectors and caches their JSON on disk. */
public final class OsmHttpClient {

    public static final int SECTOR_SIZE_METERS = 512;
    public static final URI DEFAULT_ENDPOINT = URI.create("https://overpass-api.de/api/interpreter");
    public static final Path DEFAULT_CACHE_DIRECTORY = Path.of("src/main/resources/osm_cache");

    private final Path cacheDirectory;
    private final URI endpoint;
    private final Transport transport;
    private final int maximumAttempts;
    private final Duration retryDelay;
    private final ConcurrentMap<Sector, CompletableFuture<String>> requests = new ConcurrentHashMap<>();
    private final Object requestQueueLock = new Object();
    private CompletableFuture<Void> requestTail = CompletableFuture.completedFuture(null);

    public OsmHttpClient() {
        this(Path.of(System.getProperty("world.osm.cache", DEFAULT_CACHE_DIRECTORY.toString())),
                URI.create(System.getProperty("world.osm.endpoint", DEFAULT_ENDPOINT.toString())),
                defaultTransport());
    }

    public OsmHttpClient(Path cacheDirectory, URI endpoint, Transport transport) {
        this(cacheDirectory, endpoint, transport, 3, Duration.ofSeconds(1));
    }

    OsmHttpClient(Path cacheDirectory, URI endpoint, Transport transport,
                  int maximumAttempts, Duration retryDelay) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.maximumAttempts = maximumAttempts;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (maximumAttempts <= 0) throw new IllegalArgumentException("maximumAttempts must be positive");
        if (retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
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
        return enqueueRequest(formBody).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new CompletionException(new IOException(
                        "Overpass returned HTTP " + response.statusCode()));
            }
            writeCached(cacheFile, response.body());
            return response.body();
        });
    }

    /** Overpass discourages parallel requests from one client, so sectors share one request queue. */
    private CompletableFuture<HttpResult> enqueueRequest(String formBody) {
        synchronized (requestQueueLock) {
            CompletableFuture<HttpResult> queued = requestTail
                    .handle((ignored, failure) -> null)
                    .thenCompose(ignored -> postWithRetry(formBody, 1));
            requestTail = queued.handle((response, failure) -> null);
            return queued;
        }
    }

    private CompletableFuture<HttpResult> postWithRetry(String formBody, int attempt) {
        CompletableFuture<HttpResult> posted;
        try {
            posted = transport.post(endpoint, formBody);
        } catch (RuntimeException exception) {
            posted = CompletableFuture.failedFuture(exception);
        }
        return posted.handle((response, failure) -> {
            boolean transientFailure = failure != null
                    || response != null && isRetryableStatus(response.statusCode());
            if (transientFailure && attempt < maximumAttempts) {
                long delayMillis = retryDelay.toMillis() * attempt;
                return CompletableFuture.runAsync(() -> { },
                                CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> postWithRetry(formBody, attempt + 1));
            }
            if (failure != null) return CompletableFuture.<HttpResult>failedFuture(failure);
            return CompletableFuture.completedFuture(response);
        }).thenCompose(future -> future);
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
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