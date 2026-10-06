package com.goldentime.api.prices;

import com.goldentime.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
public class MarketPriceController {
    private final JdbcTemplate jdbc;
    private final MarketPriceCrawlerService crawler;

    public MarketPriceController(JdbcTemplate jdbc, MarketPriceCrawlerService crawler) {
        this.jdbc = jdbc;
        this.crawler = crawler;
    }

    @GetMapping("/api/market/prices")
    public Comparison compare(@RequestParam String fruitSlug) {
        var fruits = jdbc.query("SELECT id, name, emoji FROM fruits WHERE slug = ?", (rs, row) ->
                new FruitRef(rs.getLong("id"), rs.getString("name"), rs.getString("emoji")), fruitSlug);
        if (fruits.isEmpty()) throw ApiException.notFound("Không tìm thấy loại trái cây.");
        FruitRef fruit = fruits.get(0);
        List<PriceQuote> prices = jdbc.query("""
                SELECT r.code, r.name, p.price_per_kg, p.source_url, p.fetched_at
                FROM market_prices p
                JOIN market_retailers r ON r.id = p.retailer_id
                WHERE p.fruit_id = ?
                ORDER BY p.price_per_kg
                """, (rs, row) -> new PriceQuote(rs.getString("code"), rs.getString("name"),
                rs.getLong("price_per_kg"), rs.getString("source_url"),
                rs.getTimestamp("fetched_at").toInstant()), fruit.id());
        if (prices.isEmpty()) {
            return new Comparison(fruitSlug, fruit.name(), fruit.emoji(), 0, null, null, null, null,
                    List.of(), "Chưa lấy được giá từ nguồn bán lẻ.");
        }
        long min = prices.stream().mapToLong(PriceQuote::pricePerKg).min().orElseThrow();
        long max = prices.stream().mapToLong(PriceQuote::pricePerKg).max().orElseThrow();
        double average = prices.stream().mapToLong(PriceQuote::pricePerKg).average().orElseThrow();
        Instant latest = prices.stream().map(PriceQuote::fetchedAt).max(Instant::compareTo).orElse(null);
        return new Comparison(fruitSlug, fruit.name(), fruit.emoji(), prices.size(), average, min, max, latest,
                prices, null);
    }

    @PostMapping("/api/admin/prices/refresh")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public MarketPriceCrawlerService.CrawlSummary refresh() {
        return crawler.crawlEnabledSources();
    }

    @GetMapping("/api/admin/prices/sources")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public List<SourceStatus> sources() {
        return jdbc.query("""
                SELECT s.id, r.name AS retailer, f.name AS fruit, s.product_name, s.source_url,
                       s.enabled, s.last_attempt_at, s.last_success_at, s.last_error
                FROM market_price_sources s
                JOIN market_retailers r ON r.id = s.retailer_id
                JOIN fruits f ON f.id = s.fruit_id
                ORDER BY r.name, f.name
                """, (rs, row) -> new SourceStatus(rs.getLong("id"), rs.getString("retailer"),
                rs.getString("fruit"), rs.getString("product_name"), rs.getString("source_url"),
                rs.getBoolean("enabled"), instant(rs.getTimestamp("last_attempt_at")),
                instant(rs.getTimestamp("last_success_at")), rs.getString("last_error")));
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record FruitRef(long id, String name, String emoji) {}
    public record PriceQuote(String retailerCode, String retailerName, long pricePerKg,
                             String sourceUrl, Instant fetchedAt) {}
    public record Comparison(String fruitSlug, String fruitName, String emoji, int sourceCount,
                             Double averagePrice, Long minPrice, Long maxPrice, Instant refreshedAt,
                             List<PriceQuote> prices, String message) {}
    public record SourceStatus(long id, String retailer, String fruit, String productName, String sourceUrl,
                               boolean enabled, Instant lastAttemptAt, Instant lastSuccessAt, String lastError) {}
}
