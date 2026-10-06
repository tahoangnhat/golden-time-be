package com.goldentime.api.catalog;

import com.goldentime.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@RestController
public class CatalogController {
    private final JdbcTemplate jdbc;

    public CatalogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/fruits")
    public List<FruitSummary> fruits(@RequestParam(required = false) String q) {
        String search = q == null ? null : q.trim().toLowerCase();
        return jdbc.query("""
                SELECT id, slug, name, emoji, category
                FROM fruits
                WHERE (CAST(? AS VARCHAR) IS NULL OR LOWER(name) LIKE ? OR LOWER(COALESCE(search_keywords, '')) LIKE ?)
                ORDER BY name
                """, (rs, row) -> new FruitSummary(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                rs.getString("emoji"), rs.getString("category")), search,
                search == null ? null : "%" + search + "%", search == null ? null : "%" + search + "%");
    }

    @GetMapping("/api/fruits/{slug}")
    public FruitDetail fruit(@PathVariable String slug) {
        List<FruitDetail> matches = jdbc.query("""
                SELECT f.id, f.slug, f.name, f.emoji, f.category,
                       n.calories_per_100g, n.vitamin_c_mg, n.fiber_g, n.sugar_g,
                       n.potassium_mg, n.water_percent, n.health_benefits,
                       n.serving_note, n.personalized_tip
                FROM fruits f
                LEFT JOIN fruit_nutrition n ON n.fruit_id = f.id
                WHERE f.slug = ?
                """, (rs, row) -> new FruitDetail(
                rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("emoji"), rs.getString("category"),
                rs.getBigDecimal("calories_per_100g"), rs.getBigDecimal("vitamin_c_mg"), rs.getBigDecimal("fiber_g"),
                rs.getBigDecimal("sugar_g"), rs.getBigDecimal("potassium_mg"), rs.getBigDecimal("water_percent"),
                rs.getString("health_benefits"), rs.getString("serving_note"), rs.getString("personalized_tip")), slug);
        if (matches.isEmpty()) throw ApiException.notFound("Không tìm thấy loại trái cây.");
        return matches.get(0);
    }

    @GetMapping("/api/shops")
    public List<ShopSummary> shops(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "10") double radiusKm) {
        if ((lat == null) != (lng == null)) throw ApiException.badRequest("Cần truyền đồng thời lat và lng.");
        List<ShopSummary> shops = jdbc.query("""
                SELECT s.id, s.name, s.address, s.latitude, s.longitude, s.rating_avg, s.review_count,
                       s.description, s.popular_fruit, COUNT(DISTINCT p.id) AS product_count
                FROM shops s
                LEFT JOIN shop_products p ON p.shop_id = s.id AND p.status = 'ON_SALE'
                WHERE s.status = 'ACTIVE'
                GROUP BY s.id
                ORDER BY s.name
                """, (rs, row) -> {
            double shopLat = rs.getDouble("latitude");
            double shopLng = rs.getDouble("longitude");
            Double distance = lat == null ? null : distanceKm(lat, lng, shopLat, shopLng);
            return new ShopSummary(rs.getLong("id"), rs.getString("name"), rs.getString("address"), shopLat, shopLng,
                    rs.getBigDecimal("rating_avg"), rs.getInt("review_count"), rs.getString("description"),
                    rs.getString("popular_fruit"), rs.getInt("product_count"), distance);
        });
        if (lat == null) return shops;
        return shops.stream().filter(shop -> shop.distanceKm() <= Math.max(0, radiusKm)).toList();
    }

    @GetMapping("/api/products")
    public List<ProductCard> products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String fruitSlug,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "25") double radiusKm) {
        if ((lat == null) != (lng == null)) throw ApiException.badRequest("Cần truyền đồng thời lat và lng.");
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.shop_id, s.name AS shop_name, s.latitude, s.longitude, f.id AS fruit_id,
                       f.slug AS fruit_slug, f.name AS fruit_name, f.emoji, f.category,
                       p.display_name, p.price_per_kg, p.stock_kg, p.ai_score, p.batch_code
                FROM shop_products p
                JOIN shops s ON s.id = p.shop_id
                JOIN fruits f ON f.id = p.fruit_id
                WHERE p.status = 'ON_SALE' AND p.stock_kg > 0 AND s.status = 'ACTIVE'
                """);
        List<Object> args = new ArrayList<>();
        if (fruitSlug != null && !fruitSlug.isBlank()) {
            sql.append(" AND f.slug = ?");
            args.add(fruitSlug.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (LOWER(p.display_name) LIKE ? OR LOWER(f.name) LIKE ? OR LOWER(COALESCE(f.search_keywords, '')) LIKE ?)");
            String pattern = "%" + q.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY p.display_name, p.price_per_kg");
        List<ProductCard> products = jdbc.query(sql.toString(), (rs, row) -> {
            double shopLat = rs.getDouble("latitude");
            double shopLng = rs.getDouble("longitude");
            Double distance = lat == null ? null : distanceKm(lat, lng, shopLat, shopLng);
            return new ProductCard(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                    rs.getLong("fruit_id"), rs.getString("fruit_slug"), rs.getString("fruit_name"), rs.getString("emoji"),
                    rs.getString("category"), rs.getString("display_name"), rs.getLong("price_per_kg"),
                    rs.getBigDecimal("stock_kg"), (Integer) rs.getObject("ai_score"), rs.getString("batch_code"), distance);
        }, args.toArray());
        if (lat == null) return products;
        return products.stream().filter(product -> product.distanceKm() <= Math.max(0, radiusKm)).toList();
    }

    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double radius = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double value = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return radius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }

    public record FruitSummary(long id, String slug, String name, String emoji, String category) {}
    public record FruitDetail(long id, String slug, String name, String emoji, String category,
                              BigDecimal caloriesPer100g, BigDecimal vitaminCMg, BigDecimal fiberG,
                              BigDecimal sugarG, BigDecimal potassiumMg, BigDecimal waterPercent,
                              String healthBenefits, String servingNote, String personalizedTip) {}
    public record ShopSummary(long id, String name, String address, double latitude, double longitude,
                              BigDecimal rating, int reviewCount, String description, String popularFruit,
                              int productCount, Double distanceKm) {}
    public record ProductCard(long id, long shopId, String shopName, long fruitId, String fruitSlug,
                              String fruitName, String emoji, String category, String displayName,
                              long pricePerKg, BigDecimal stockKg, Integer aiScore, String batchCode,
                              Double distanceKm) {}
}
