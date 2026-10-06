package com.goldentime.api.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only admin views backed by persisted application data. */
@RestController
@RequestMapping("/api/admin")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminDataController {
    private final JdbcTemplate jdbc;

    public AdminDataController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users")
    public List<UserRow> users() {
        return jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.role, u.phone, u.enabled, u.created_at,
                       (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS order_count,
                       (SELECT COUNT(*) FROM ai_scans a WHERE a.user_id = u.id) AS scan_count,
                       (SELECT COUNT(*) FROM reviews r WHERE r.user_id = u.id) AS review_count
                FROM users u ORDER BY u.created_at DESC, u.id DESC
                """, (rs, row) -> new UserRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"),
                rs.getString("role"), rs.getString("phone"), rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("order_count"), rs.getLong("scan_count"), rs.getLong("review_count")));
    }

    @GetMapping("/shops")
    public List<ShopRow> shops() {
        return jdbc.query("""
                SELECT s.id, s.name, s.address, s.latitude, s.longitude, s.status, s.rating_avg, s.review_count,
                       u.full_name AS owner_name,
                       (SELECT COUNT(*) FROM shop_products p WHERE p.shop_id = s.id) AS product_count,
                       (SELECT COALESCE(SUM(o.total_amount), 0) FROM orders o
                        WHERE o.shop_id = s.id AND o.status <> 'CANCELLED') AS order_value
                FROM shops s LEFT JOIN users u ON u.id = s.owner_user_id
                ORDER BY s.created_at DESC, s.id DESC
                """, (rs, row) -> new ShopRow(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("status"),
                rs.getBigDecimal("rating_avg"), rs.getInt("review_count"), rs.getString("owner_name"),
                rs.getInt("product_count"), rs.getLong("order_value")));
    }

    @GetMapping("/products")
    public List<ProductRow> products() {
        return jdbc.query("""
                SELECT p.id, f.emoji, p.display_name, s.name AS shop_name, p.price_per_kg, p.stock_kg,
                       p.ai_score, p.status, f.category
                FROM shop_products p JOIN shops s ON s.id = p.shop_id JOIN fruits f ON f.id = p.fruit_id
                ORDER BY p.created_at DESC, p.id DESC
                """, (rs, row) -> new ProductRow(rs.getLong("id"), rs.getString("emoji"),
                rs.getString("display_name"), rs.getString("shop_name"), rs.getLong("price_per_kg"),
                rs.getBigDecimal("stock_kg"), (Integer) rs.getObject("ai_score"), rs.getString("status"),
                rs.getString("category")));
    }

    @GetMapping("/orders")
    public List<OrderRow> orders() {
        return jdbc.query("""
                SELECT o.id, u.full_name AS customer_name, s.name AS shop_name, o.total_amount,
                       o.subtotal_amount, o.shipping_fee, o.status, o.payment_method, o.note,
                       o.delivery_name, o.delivery_phone, o.delivery_address, o.created_at,
                       COUNT(oi.id) AS item_count
                FROM orders o JOIN users u ON u.id = o.user_id JOIN shops s ON s.id = o.shop_id
                LEFT JOIN order_items oi ON oi.order_id = o.id
                GROUP BY o.id, u.full_name, s.name
                ORDER BY o.created_at DESC, o.id DESC
                """, (rs, row) -> new OrderRow(rs.getLong("id"), rs.getString("customer_name"), rs.getString("shop_name"),
                rs.getLong("total_amount"), rs.getLong("subtotal_amount"), rs.getLong("shipping_fee"),
                rs.getString("status"), rs.getString("payment_method"), rs.getString("note"),
                rs.getString("delivery_name"), rs.getString("delivery_phone"), rs.getString("delivery_address"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("item_count")));
    }

    @GetMapping("/reviews")
    public List<ReviewRow> reviews() {
        return jdbc.query("""
                SELECT r.id, u.full_name AS author, s.name AS shop_name, r.rating, r.comment, r.created_at,
                       (r.order_id IS NOT NULL) AS purchased
                FROM reviews r JOIN users u ON u.id = r.user_id JOIN shops s ON s.id = r.shop_id
                ORDER BY r.created_at DESC, r.id DESC
                """, (rs, row) -> new ReviewRow(rs.getLong("id"), rs.getString("author"), rs.getString("shop_name"),
                rs.getInt("rating"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("purchased")));
    }

    @GetMapping("/scans")
    public List<ScanRow> scans() {
        return jdbc.query("""
                SELECT a.id, a.detected_name, f.emoji, a.freshness_score, a.quality_score, a.confidence,
                       u.full_name AS user_name, a.analysis_mode, a.created_at
                FROM ai_scans a LEFT JOIN fruits f ON f.id = a.fruit_id
                JOIN users u ON u.id = a.user_id
                ORDER BY a.created_at DESC, a.id DESC LIMIT 500
                """, (rs, row) -> new ScanRow(rs.getLong("id"), rs.getString("detected_name"), rs.getString("emoji"),
                nullableInt(rs, "freshness_score"), nullableInt(rs, "quality_score"), nullableDouble(rs, "confidence"),
                rs.getString("user_name"), rs.getString("analysis_mode"), rs.getTimestamp("created_at").toInstant()));
    }

    @GetMapping("/nutrition")
    public List<NutritionRow> nutrition() {
        return jdbc.query("""
                SELECT f.id, f.slug, f.name, f.emoji, n.calories_per_100g, n.vitamin_c_mg,
                       n.fiber_g, n.sugar_g, n.potassium_mg, n.water_percent, n.health_benefits
                FROM fruits f LEFT JOIN fruit_nutrition n ON n.fruit_id = f.id ORDER BY f.name
                """, (rs, row) -> new NutritionRow(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                rs.getString("emoji"), rs.getBigDecimal("calories_per_100g"), rs.getBigDecimal("vitamin_c_mg"),
                rs.getBigDecimal("fiber_g"), rs.getBigDecimal("sugar_g"), rs.getBigDecimal("potassium_mg"),
                rs.getBigDecimal("water_percent"), rs.getString("health_benefits")));
    }

    @GetMapping("/traceability")
    public List<TraceRow> traceability() {
        return jdbc.query("""
                SELECT b.batch_code, b.product_name, b.origin, b.supplier, b.certified,
                       b.harvest_date, b.intake_date, b.certificates, s.name AS shop_name
                FROM trace_batches b
                LEFT JOIN shop_products p ON p.batch_code = b.batch_code
                LEFT JOIN shops s ON s.id = p.shop_id
                GROUP BY b.batch_code, b.product_name, b.origin, b.supplier, b.certified,
                         b.harvest_date, b.intake_date, b.certificates, s.name
                ORDER BY b.created_at DESC, b.batch_code
                """, (rs, row) -> new TraceRow(rs.getString("batch_code"), rs.getString("product_name"),
                rs.getString("shop_name"), rs.getString("supplier"), rs.getString("origin"), rs.getDate("harvest_date") == null ? null : rs.getDate("harvest_date").toLocalDate(),
                rs.getDate("intake_date") == null ? null : rs.getDate("intake_date").toLocalDate(),
                rs.getString("certificates"), rs.getBoolean("certified")));
    }

    @GetMapping("/overview")
    public Overview overview() {
        Counts counts = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM users) AS users,
                       (SELECT COUNT(*) FROM shops) AS shops,
                       (SELECT COUNT(*) FROM orders) AS orders,
                       (SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE created_at::date = CURRENT_DATE AND status <> 'CANCELLED') AS order_value_today,
                       (SELECT COUNT(*) FROM ai_scans WHERE created_at::date = CURRENT_DATE) AS scans_today,
                       (SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED') AS confirmed_orders
                """, (rs, row) -> new Counts(rs.getLong("users"), rs.getLong("shops"), rs.getLong("orders"),
                rs.getLong("order_value_today"), rs.getLong("scans_today"), rs.getLong("confirmed_orders")));
        List<DailyMetric> days = jdbc.query("""
                SELECT d.day::date AS day,
                       COALESCE((SELECT SUM(o.total_amount) FROM orders o WHERE o.created_at::date = d.day::date AND o.status <> 'CANCELLED'), 0) AS order_value,
                       (SELECT COUNT(*) FROM ai_scans a WHERE a.created_at::date = d.day::date) AS scans
                FROM generate_series(CURRENT_DATE - INTERVAL '6 days', CURRENT_DATE, INTERVAL '1 day') d(day)
                ORDER BY d.day
                """, (rs, row) -> new DailyMetric(rs.getDate("day").toLocalDate(), rs.getLong("order_value"), rs.getLong("scans")));
        List<TopFruit> fruits = jdbc.query("""
                SELECT COALESCE(f.name, a.detected_name, 'Chưa nhận diện') AS name, COUNT(*) AS scans
                FROM ai_scans a LEFT JOIN fruits f ON f.id = a.fruit_id
                GROUP BY COALESCE(f.name, a.detected_name, 'Chưa nhận diện')
                ORDER BY scans DESC LIMIT 5
                """, (rs, row) -> new TopFruit(rs.getString("name"), rs.getLong("scans")));
        List<TopShop> shops = jdbc.query("""
                SELECT s.name, COUNT(o.id) AS orders, COALESCE(SUM(o.total_amount) FILTER (WHERE o.status <> 'CANCELLED'), 0) AS order_value
                FROM shops s LEFT JOIN orders o ON o.shop_id = s.id
                GROUP BY s.id, s.name ORDER BY order_value DESC LIMIT 5
                """, (rs, row) -> new TopShop(rs.getString("name"), rs.getLong("orders"), rs.getLong("order_value")));
        return new Overview(counts.users(), counts.shops(), counts.orders(), counts.orderValueToday(), counts.scansToday(),
                counts.orders() == 0 ? 0 : counts.confirmedOrders() * 100.0 / counts.orders(), fruits, shops,
                jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'PENDING'", Integer.class), days);
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record UserRow(long id, String name, String email, String role, String phone, boolean enabled, Instant joined,
                          long orders, long scans, long reviews) {}
    public record ShopRow(long id, String name, String address, double latitude, double longitude, String status,
                          BigDecimal rating, int reviewCount, String owner, int products, long orderValue) {}
    public record ProductRow(long id, String emoji, String name, String shop, long price, BigDecimal stock,
                             Integer score, String status, String category) {}
    public record OrderRow(long id, String customer, String shop, long total, long subtotal, long shippingFee,
                           String status, String payment, String note, String deliveryName, String deliveryPhone,
                           String deliveryAddress, Instant createdAt, int itemCount) {}
    public record ReviewRow(long id, String user, String shop, int rating, String comment, Instant createdAt,
                            boolean purchased) {}
    public record ScanRow(long id, String fruit, String emoji, Integer freshness, Integer quality, Double confidence,
                          String user, String mode, Instant createdAt) {}
    public record NutritionRow(long id, String slug, String name, String emoji, BigDecimal calories, BigDecimal vitaminC,
                               BigDecimal fiber, BigDecimal sugar, BigDecimal potassium, BigDecimal water, String benefits) {}
    public record TraceRow(String batchCode, String product, String shop, String supplier, String area,
                           LocalDate harvestDate, LocalDate intakeDate, String certificates, boolean certified) {}
    public record TopFruit(String name, long scans) {}
    public record TopShop(String name, long orders, long orderValue) {}
    public record DailyMetric(LocalDate date, long orderValue, long scans) {}
    public record Overview(long users, long shops, long orders, long orderValueToday, long scansToday, double confirmedPercent,
                           List<TopFruit> topFruits, List<TopShop> topShops, int pendingOrders, List<DailyMetric> daily) {}
    private record Counts(long users, long shops, long orders, long orderValueToday, long scansToday, long confirmedOrders) {}
}
