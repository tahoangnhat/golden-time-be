package com.goldentime.api.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MarketPriceCrawlerService {
    private static final Pattern CURRENCY_PRICE = Pattern.compile("(?iu)([0-9][0-9.,\\s]{1,14})\\s*(?:₫|đ|vnd)");
    private static final Pattern DIGITS = Pattern.compile("[^0-9]");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String userAgent;
    private final int timeoutMs;
    private final int minIntervalSeconds;
    private final boolean crawlOnStartup;

    public MarketPriceCrawlerService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.prices.user-agent}") String userAgent,
            @Value("${app.prices.timeout-ms:12000}") int timeoutMs,
            @Value("${app.prices.min-interval-seconds:3}") int minIntervalSeconds,
            @Value("${app.prices.crawl-on-startup:false}") boolean crawlOnStartup) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
        this.minIntervalSeconds = minIntervalSeconds;
        this.crawlOnStartup = crawlOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void crawlAtStartup() {
        if (crawlOnStartup) crawlEnabledSources();
    }

    @Scheduled(cron = "${app.prices.crawl-cron:0 0 */12 * * *}")
    public void scheduledCrawl() {
        crawlEnabledSources();
    }

    public CrawlSummary crawlEnabledSources() {
        List<PriceSource> sources = jdbc.query("""
                SELECT s.id, s.fruit_id, s.retailer_id, r.code AS retailer_code,
                       s.product_name, s.source_url, s.package_grams, s.price_is_per_kg
                FROM market_price_sources s
                JOIN market_retailers r ON r.id = s.retailer_id
                WHERE s.enabled = TRUE
                ORDER BY r.code, s.id
                """, (rs, row) -> new PriceSource(rs.getLong("id"), rs.getLong("fruit_id"),
                rs.getLong("retailer_id"), rs.getString("retailer_code"), rs.getString("product_name"),
                rs.getString("source_url"), rs.getBigDecimal("package_grams"), rs.getBoolean("price_is_per_kg")));
        int updated = 0;
        int failed = 0;
        Map<String, List<String>> robotsCache = new HashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            PriceSource source = sources.get(index);
            if (index > 0 && minIntervalSeconds > 0) {
                try {
                    Thread.sleep(minIntervalSeconds * 1000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new CrawlSummary(sources.size(), updated, failed + sources.size() - index, Instant.now());
                }
            }
            jdbc.update("UPDATE market_price_sources SET last_attempt_at = NOW(), last_error = NULL WHERE id = ?", source.id());
            try {
                URI uri = URI.create(source.sourceUrl());
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (!isSupportedHost(host)) throw new IllegalArgumentException("Chỉ cho phép nguồn từ bachhoaxanh.com hoặc winmart.vn.");
                List<String> disallows = robotsCache.computeIfAbsent(host, ignored -> loadRobotsDisallows(uri));
                if (isDisallowed(uri.getPath(), disallows)) throw new IllegalStateException("robots.txt không cho phép crawler truy cập trang này.");
                Document document = Jsoup.connect(source.sourceUrl())
                        .userAgent(userAgent)
                        .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.7")
                        .timeout(timeoutMs)
                        .maxBodySize(5 * 1024 * 1024)
                        .followRedirects(true)
                        .method(Connection.Method.GET)
                        .get();
                BigDecimal listedPrice = extractPrice(document, source.productName());
                long pricePerKg = normalizePerKg(listedPrice, source.packageGrams(), source.priceIsPerKg());
                jdbc.update("""
                        INSERT INTO market_prices (fruit_id, retailer_id, price_per_kg, source_url, fetched_at)
                        VALUES (?, ?, ?, ?, NOW())
                        ON CONFLICT (fruit_id, retailer_id) DO UPDATE SET
                            price_per_kg = EXCLUDED.price_per_kg,
                            source_url = EXCLUDED.source_url,
                            fetched_at = EXCLUDED.fetched_at
                        """, source.fruitId(), source.retailerId(), pricePerKg, source.sourceUrl());
                jdbc.update("UPDATE market_price_sources SET last_success_at = NOW(), last_error = NULL WHERE id = ?", source.id());
                updated++;
            } catch (Exception exception) {
                String message = truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 1000);
                jdbc.update("UPDATE market_price_sources SET last_error = ? WHERE id = ?", message, source.id());
                failed++;
            }
        }
        return new CrawlSummary(sources.size(), updated, failed, Instant.now());
    }

    private List<String> loadRobotsDisallows(URI source) {
        try {
            String robotsUrl = source.getScheme() + "://" + source.getAuthority() + "/robots.txt";
            String text = Jsoup.connect(robotsUrl).userAgent(userAgent).timeout(timeoutMs)
                    .ignoreHttpErrors(true).execute().body();
            List<String> disallows = new ArrayList<>();
            boolean relevant = false;
            for (String rawLine : text.split("\\s*\\n\\s*")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim().toLowerCase(Locale.ROOT);
                String value = parts[1].trim();
                if (key.equals("user-agent")) relevant = value.equals("*") || userAgent.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
                else if (key.equals("disallow") && relevant && !value.isBlank()) disallows.add(value);
            }
            return disallows;
        } catch (Exception ignored) {
            // A missing/unavailable robots.txt is treated as no published disallow rules.
            return List.of();
        }
    }

    private static boolean isDisallowed(String path, List<String> disallows) {
        return disallows.stream().anyMatch(rule -> path != null && !rule.isBlank() && path.startsWith(rule));
    }

    private static boolean isSupportedHost(String host) {
        return host.equals("bachhoaxanh.com") || host.endsWith(".bachhoaxanh.com")
                || host.equals("winmart.vn") || host.endsWith(".winmart.vn");
    }

    private BigDecimal extractPrice(Document document, String expectedName) throws Exception {
        List<JsonNode> jsonProducts = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonNode root = mapper.readTree(script.data().isBlank() ? script.html() : script.data());
                collectProductNodes(root, jsonProducts);
            } catch (Exception ignored) {
                // Continue with the other structured-data and metadata sources.
            }
        }
        String expected = normalize(expectedName);
        JsonNode bestOffer = null;
        int bestNameScore = -1;
        for (JsonNode product : jsonProducts) {
            String name = normalize(product.path("name").asText(""));
            int score = name.isBlank() ? 0 : (name.equals(expected) ? 3 : (name.contains(expected) || expected.contains(name) ? 2 : 1));
            JsonNode offer = product.path("offers");
            if (offer.isArray()) offer = offer.isEmpty() ? null : offer.get(0);
            if (offer != null && offer.has("price") && score > bestNameScore) {
                bestNameScore = score;
                bestOffer = offer;
            }
        }
        if (bestOffer != null) {
            BigDecimal value = decimal(bestOffer.path("price").asText());
            if (value != null && value.signum() > 0) return value;
        }
        for (String selector : List.of("meta[itemprop=price][content]", "meta[property=product:price:amount]", "meta[name=price][content]")) {
            Element meta = document.selectFirst(selector);
            if (meta != null) {
                BigDecimal value = decimal(meta.attr("content"));
                if (value != null && value.signum() > 0) return value;
            }
        }
        for (String selector : List.of("[itemprop=price][content]", "[itemprop=price]", "[data-testid*=price]", ".product-price", ".price")) {
            Element element = document.selectFirst(selector);
            if (element != null) {
                BigDecimal value = decimal(element.hasAttr("content") ? element.attr("content") : element.text());
                if (value != null && value.signum() > 0) return value;
            }
        }
        Matcher matcher = CURRENCY_PRICE.matcher(document.text());
        while (matcher.find()) {
            BigDecimal value = decimal(matcher.group(1));
            if (value != null && value.signum() > 0) return value;
        }
        throw new IllegalStateException("Không tìm thấy giá sản phẩm trong HTML; có thể trang đã đổi cấu trúc hoặc cần API chính thức.");
    }

    private static void collectProductNodes(JsonNode node, List<JsonNode> target) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectProductNodes(child, target));
        } else if (node.isObject()) {
            JsonNode type = node.path("@type");
            boolean product = type.isTextual() ? type.asText().equalsIgnoreCase("Product") : false;
            if (type.isArray()) for (JsonNode value : type) product |= value.asText().equalsIgnoreCase("Product");
            if (product) target.add(node);
            node.fields().forEachRemaining(field -> {
                if (field.getValue().isContainerNode()) collectProductNodes(field.getValue(), target);
            });
        }
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim().replace("₫", "").replace("đ", "").replace("VND", "").replace("vnd", "").trim();
        clean = DIGITS.matcher(clean).replaceAll("");
        if (clean.isBlank()) return null;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long normalizePerKg(BigDecimal price, BigDecimal packageGrams, boolean alreadyPerKg) {
        BigDecimal normalized;
        if (alreadyPerKg) normalized = price;
        else if (packageGrams != null && packageGrams.signum() > 0) {
            normalized = price.multiply(BigDecimal.valueOf(1000)).divide(packageGrams, 0, RoundingMode.HALF_UP);
        } else throw new IllegalStateException("Thiếu khối lượng gói để quy đổi giá về đồng/kg.");
        if (normalized.signum() <= 0 || normalized.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
            throw new IllegalStateException("Giá sau quy đổi nằm ngoài khoảng hợp lệ.");
        }
        return normalized.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PriceSource(long id, long fruitId, long retailerId, String retailerCode,
                               String productName, String sourceUrl, BigDecimal packageGrams, boolean priceIsPerKg) {}

    public record CrawlSummary(int sources, int updated, int failed, Instant completedAt) {}
}
