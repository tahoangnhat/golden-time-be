package com.goldentime.api.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldentime.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OsmNearbyStoreService {
    private static final double SEARCH_RADIUS_METERS = 11_500;
    private static final long MIN_REQUEST_INTERVAL_MS = 2_500;
    private static final long CACHE_TTL_MS = Duration.ofHours(6).toMillis();
    private static final int MAX_CACHE_ENTRIES = 100;
    private static final String TARGET_BRANDS = "Klever Fruit|F5 Fruit Shop|F5 Fruits?|360 ?Fruit|Tony Fruit|Morning Fruit|EUS Fruit|Farmers Market|Phu ?Hoa Fresh|Organica|Nông Sản Xanh|Nong San Xanh|WinMart\\\\+?|WinMart Plus|Bách Hóa Xanh|Bach Hoa Xanh|Co\\\\.?opmart|Coopmart|Co\\\\.?op Food|Coop Food|GO!|Tops Market|AEON|Lotte Mart|MM Mega Market";
    private static final Logger log = LoggerFactory.getLogger(OsmNearbyStoreService.class);

    private final RestClient restClient;
    private final Object requestLock = new Object();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(32, 0.75f, true);
    private long nextRequestAt;

    public OsmNearbyStoreService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.clone()
                .baseUrl("https://overpass-api.de")
                .defaultHeader("User-Agent", "GoldenTime/1.0 (+https://goldentime.id.vn)")
                .build();
    }

    public List<NearbyStore> nearby(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw ApiException.badRequest("Tọa độ không hợp lệ.");
        }
        String lat = String.format(Locale.ROOT, "%.3f", latitude);
        String lon = String.format(Locale.ROOT, "%.3f", longitude);
        String key = lat + ":" + lon;

        long now = System.currentTimeMillis();
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.expiresAt() > now) return entry.stores();
            if (entry != null) cache.remove(key);
        }

        String query = """
                [out:json][timeout:25];
                (
                  nwr(around:%s,%s,%s)["shop"]["name"~"%s",i];
                  nwr(around:%s,%s,%s)["shop"]["brand"~"%s",i];
                  nwr(around:%s,%s,%s)["shop"]["operator"~"%s",i];
                  nwr(around:%s,%s,%s)["amenity"="marketplace"]["name"~"%s",i];
                );
                out center tags 200;
                """.formatted(SEARCH_RADIUS_METERS, lat, lon, TARGET_BRANDS,
                SEARCH_RADIUS_METERS, lat, lon, TARGET_BRANDS,
                SEARCH_RADIUS_METERS, lat, lon, TARGET_BRANDS,
                SEARCH_RADIUS_METERS, lat, lon, TARGET_BRANDS);

        JsonNode response;
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("data", query);
            response = requestOverpass(body);
        } catch (RestClientResponseException exception) {
            log.warn("Overpass returned HTTP status {} for a nearby-store query.", exception.getStatusCode().value());
            throw ApiException.unavailable("Chưa lấy được danh sách cửa hàng OpenStreetMap. Vui lòng thử lại sau.");
        } catch (RestClientException exception) {
            log.warn("Could not reach Overpass for a nearby-store query ({}).", exception.getClass().getSimpleName());
            throw ApiException.unavailable("Chưa lấy được danh sách cửa hàng OpenStreetMap. Vui lòng thử lại sau.");
        }
        if (response == null) throw ApiException.unavailable("OpenStreetMap không trả dữ liệu cửa hàng.");

        List<NearbyStore> stores = parseStores(response);
        List<NearbyStore> immutableStores = List.copyOf(stores);
        synchronized (cache) {
            cache.put(key, new CacheEntry(immutableStores, System.currentTimeMillis() + CACHE_TTL_MS));
            while (cache.size() > MAX_CACHE_ENTRIES) {
                Iterator<String> oldest = cache.keySet().iterator();
                if (!oldest.hasNext()) break;
                oldest.next();
                oldest.remove();
            }
        }
        return immutableStores;
    }

    private List<NearbyStore> parseStores(JsonNode response) {
        List<NearbyStore> stores = new ArrayList<>();
        JsonNode elements = response.path("elements");
        if (!elements.isArray()) return stores;

        for (JsonNode element : elements) {
            JsonNode tags = element.path("tags");
            JsonNode center = element.path("center");
            double latitude = element.has("lat") ? element.path("lat").asDouble(Double.NaN) : center.path("lat").asDouble(Double.NaN);
            double longitude = element.has("lon") ? element.path("lon").asDouble(Double.NaN) : center.path("lon").asDouble(Double.NaN);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) continue;

            String name = firstNonBlank(tags.path("name").asText(null), tags.path("brand").asText(null), tags.path("operator").asText(null));
            if (name == null) continue;
            String id = element.path("type").asText("osm") + "/" + element.path("id").asText();
            String address = firstNonBlank(tags.path("addr:full").asText(null), formatAddress(tags));
            stores.add(new NearbyStore(id, name, address, latitude, longitude));
        }
        return stores;
    }

    private String formatAddress(JsonNode tags) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, tags.path("addr:housenumber").asText(null));
        addIfPresent(parts, tags.path("addr:street").asText(null));
        addIfPresent(parts, tags.path("addr:suburb").asText(null));
        addIfPresent(parts, tags.path("addr:city").asText(null));
        addIfPresent(parts, tags.path("addr:province").asText(null));
        return String.join(", ", parts);
    }

    private void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) parts.add(value.strip());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private JsonNode requestOverpass(MultiValueMap<String, String> body) {
        synchronized (requestLock) {
            long delay = nextRequestAt - System.currentTimeMillis();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw ApiException.unavailable("Yêu cầu cửa hàng gần bạn đã bị gián đoạn.");
                }
            }
            nextRequestAt = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MS;
            return restClient.post()
                    .uri("/api/interpreter")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        }
    }

    public record NearbyStore(String id, String name, String address, double latitude, double longitude) {}
    private record CacheEntry(List<NearbyStore> stores, long expiresAt) {}
}
