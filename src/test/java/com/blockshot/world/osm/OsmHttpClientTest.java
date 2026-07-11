package com.blockshot.world.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OsmHttpClientTest {

    @TempDir
    Path cacheDirectory;

    @Test
    void givenCachedSector_whenFetched_thenDiskIsUsedWithoutNetworkCall() throws Exception {
        String cached = "{\"elements\":[]}";
        Files.writeString(cacheDirectory.resolve("sector_2_-3.json"), cached);
        AtomicInteger requests = new AtomicInteger();
        OsmHttpClient client = client((uri, body) -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(new OsmHttpClient.HttpResult(200, "network"));
        });

        String result = client.fetchSector(2, -3, new OsmCoordinate(0.0, 0.0)).join();

        assertEquals(cached, result);
        assertEquals(0, requests.get());
    }

    @Test
    void givenCacheMiss_whenFetched_thenOverpassResponseIsPersistedAndRequestIsDeduplicated() {
        String responseJson = "{\"elements\":[]}";
        AtomicInteger requests = new AtomicInteger();
        CompletableFuture<OsmHttpClient.HttpResult> response = new CompletableFuture<>();
        OsmHttpClient client = client((uri, body) -> {
            requests.incrementAndGet();
            String query = URLDecoder.decode(body.substring("data=".length()), StandardCharsets.UTF_8);
            assertTrue(query.contains("way[\"highway\"]"));
            assertTrue(query.contains("relation[\"building\"]"));
            return response;
        });
        OsmCoordinate projection = new OsmCoordinate(64.1466, -21.9426);

        CompletableFuture<String> first = client.fetchSector(0, 0, projection);
        CompletableFuture<String> second = client.fetchSector(0, 0, projection);
        response.complete(new OsmHttpClient.HttpResult(200, responseJson));

        assertEquals(responseJson, first.join());
        assertEquals(responseJson, second.join());
        assertEquals(1, requests.get());
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("sector_0_0.json")));
    }

    @Test
    void givenOverpassFailure_whenFetched_thenFutureFailsAndNothingIsCached() {
        OsmHttpClient client = client((uri, body) -> CompletableFuture.completedFuture(
                new OsmHttpClient.HttpResult(400, "bad request")));

        CompletableFuture<String> result = client.fetchSector(-1, 4, new OsmCoordinate(0.0, 0.0));

        assertThrows(CompletionException.class, result::join);
        assertFalse(Files.exists(cacheDirectory.resolve("sector_-1_4.json")));
    }

    @Test
    void givenTransientRateLimit_whenFetched_thenRequestIsRetriedAndCached() {
        AtomicInteger requests = new AtomicInteger();
        OsmHttpClient client = new OsmHttpClient(cacheDirectory,
                URI.create("https://example.test/api/interpreter"), (uri, body) -> {
                    int request = requests.incrementAndGet();
                    return CompletableFuture.completedFuture(request == 1
                            ? new OsmHttpClient.HttpResult(429, "rate limited")
                            : new OsmHttpClient.HttpResult(200, "{\"elements\":[]}"));
                }, 3, Duration.ZERO);

        String result = client.fetchSector(0, 0, new OsmCoordinate(0.0, 0.0)).join();

        assertEquals("{\"elements\":[]}", result);
        assertEquals(2, requests.get());
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("sector_0_0.json")));
    }

    @Test
    void givenTwoUncachedSectors_whenFetched_thenNetworkRequestsNeverOverlap() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<OsmHttpClient.HttpResult> firstResponse = new CompletableFuture<>();
        CompletableFuture<OsmHttpClient.HttpResult> secondResponse = new CompletableFuture<>();
        OsmHttpClient client = client((uri, body) -> {
            int call = calls.incrementAndGet();
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            if (call == 1) firstStarted.countDown();
            else secondStarted.countDown();
            CompletableFuture<OsmHttpClient.HttpResult> response = call == 1
                    ? firstResponse : secondResponse;
            return response.whenComplete((value, failure) -> active.decrementAndGet());
        });

        CompletableFuture<String> first = client.fetchSector(0, 0, new OsmCoordinate(0.0, 0.0));
        CompletableFuture<String> second = client.fetchSector(1, 0, new OsmCoordinate(0.0, 0.0));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());

        firstResponse.complete(new OsmHttpClient.HttpResult(200, "{\"elements\":[]}"));
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        secondResponse.complete(new OsmHttpClient.HttpResult(200, "{\"elements\":[]}"));
        CompletableFuture.allOf(first, second).join();

        assertEquals(2, calls.get());
        assertEquals(1, maximumActive.get());
    }

    private OsmHttpClient client(OsmHttpClient.Transport transport) {
        return new OsmHttpClient(cacheDirectory,
                URI.create("https://example.test/api/interpreter"), transport);
    }
}