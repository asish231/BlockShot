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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
                new OsmHttpClient.HttpResult(429, "rate limited")));

        CompletableFuture<String> result = client.fetchSector(-1, 4, new OsmCoordinate(0.0, 0.0));

        assertThrows(CompletionException.class, result::join);
        assertFalse(Files.exists(cacheDirectory.resolve("sector_-1_4.json")));
    }

    private OsmHttpClient client(OsmHttpClient.Transport transport) {
        return new OsmHttpClient(cacheDirectory,
                URI.create("https://example.test/api/interpreter"), transport);
    }
}