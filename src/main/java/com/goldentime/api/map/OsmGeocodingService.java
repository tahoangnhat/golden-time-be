package com.goldentime.api.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldentime.api.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Service
public class OsmGeocodingService {
    private static final long MIN_REQUEST_INTERVAL_MS = 1_100;
    private static final long CACHE_TTL_MS = Duration.ofHours(24).toMillis();
    private static final int MAX_CACHE_ENTRIES = 500;

    private final RestClient restClient;
    private final Object requestLock = new Object();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long nextRequestAt;

    public OsmGeocodingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.clone()
                .baseUrl("https://nominatim.openstreetmap.org")
                .defaultHeader("User-Agent", "GoldenTime/1.0 (+https://goldentime.id.vn)")
                .defaultHeader("Accept-Language", "vi,en")
                .build();
    }

    public JsonNode search(String query) {
        String normalized = query == null ? "" : query.strip();
        if (normalized.length() < 3 || normalized.length() > 200) {
            throw ApiException.badRequest("Từ khóa tìm địa chỉ phải có từ 3 đến 200 ký tự.");
        }
        String cacheKey = "search:" + normalized.toLowerCase(Locale.ROOT);
        return cached(cacheKey, uri -> uri.path("/search")
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("countrycodes", "vn")
                .queryParam("limit", "5")
                .queryParam("q", normalized));
    }

    public JsonNode reverse(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw ApiException.badRequest("Tọa độ không hợp lệ.");
        }
        String lat = String.format(Locale.ROOT, "%.6f", latitude);
        String lon = String.format(Locale.ROOT, "%.6f", longitude);
        return cached("reverse:" + lat + ":" + lon, uri -> uri.path("/reverse")
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("zoom", "18")
                .queryParam("lat", lat)
                .queryParam("lon", lon));
    }

    private JsonNode cached(String key, Function<UriBuilder, java.net.URI> uriBuilder) {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.expiresAt() > now) return entry.value().deepCopy();
            if (entry != null) cache.remove(key);
        }

        JsonNode result;
        try {
            waitForRequestSlot();
            result = restClient.get().uri(uriBuilder).retrieve().body(JsonNode.class);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("Dịch vụ địa chỉ OpenStreetMap chưa trả được kết quả. Vui lòng thử lại sau.");
        }
        if (result == null) throw ApiException.unavailable("Dịch vụ địa chỉ OpenStreetMap trả về dữ liệu rỗng.");

        synchronized (cache) {
            cache.put(key, new CacheEntry(result.deepCopy(), System.currentTimeMillis() + CACHE_TTL_MS));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                Iterator<String> oldest = cache.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
        }
        return result;
    }

    private void waitForRequestSlot() {
        synchronized (requestLock) {
            long delay = nextRequestAt - System.currentTimeMillis();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw ApiException.unavailable("Yêu cầu tìm địa chỉ đã bị gián đoạn.");
                }
            }
            nextRequestAt = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MS;
        }
    }

    private record CacheEntry(JsonNode value, long expiresAt) {}
}
