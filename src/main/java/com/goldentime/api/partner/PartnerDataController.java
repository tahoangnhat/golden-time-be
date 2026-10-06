package com.goldentime.api.partner;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only partner views sourced from the authenticated shop owner's records. */
@RestController
@RequestMapping("/api/shop")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class PartnerDataController {
    private final JdbcTemplate jdbc;

    public PartnerDataController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/profile")
    public ShopProfile profile(Authentication authentication) {
        return shop(CurrentUser.id(authentication));
    }

    @GetMapping("/products")
    public List<ShopProduct> products(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        return jdbc.query("""
                SELECT p.id, f.id AS fruit_id, f.name AS fruit_name, f.emoji, f.category,
                       p.display_name, p.price_per_kg, p.stock_kg, p.ai_score, p.status, p.batch_code
                FROM shop_products p JOIN fruits f ON f.id = p.fruit_id
                WHERE p.shop_id = ? ORDER BY p.created_at DESC, p.id DESC
                """, (rs, row) -> new ShopProduct(rs.getLong("id"), rs.getLong("fruit_id"),
                rs.getString("fruit_name"), rs.getString("emoji"), rs.getString("category"),
                rs.getString("display_name"), rs.getLong("price_per_kg"), rs.getBigDecimal("stock_kg"),
                (Integer) rs.getObject("ai_score"), rs.getString("status"), rs.getString("batch_code")), shopId);
    }

    @GetMapping("/orders")
    public List<ShopOrder> orders(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        List<ShopOrder> orders = jdbc.query("""
                SELECT o.id, o.user_id, u.full_name AS customer, o.total_amount, o.subtotal_amount,
                       o.shipping_fee, o.status, o.payment_method, o.delivery_name, o.delivery_phone,
                       o.delivery_address, o.note, o.created_at
                FROM orders o JOIN users u ON u.id = o.user_id
                WHERE o.shop_id = ? ORDER BY o.created_at DESC, o.id DESC
                """, (rs, row) -> new ShopOrder(rs.getLong("id"), rs.getString("customer"),
                rs.getLong("total_amount"), rs.getLong("subtotal_amount"), rs.getLong("shipping_fee"),
                rs.getString("status"), rs.getString("payment_method"), rs.getString("delivery_name"),
                rs.getString("delivery_phone"), rs.getString("delivery_address"), rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(), List.of()), shopId);
        return orders.stream().map(order -> order.withItems(jdbc.query("""
                        SELECT p.display_name, f.emoji, oi.quantity_kg, oi.unit_price, oi.line_total
                        FROM order_items oi JOIN shop_products p ON p.id = oi.shop_product_id
                        JOIN fruits f ON f.id = p.fruit_id WHERE oi.order_id = ? ORDER BY oi.id
                        """, (rs, row) -> new ShopOrderItem(rs.getString("display_name"), rs.getString("emoji"),
                        rs.getBigDecimal("quantity_kg"), rs.getLong("unit_price"), rs.getLong("line_total")), order.id())))
                .toList();
    }

    @GetMapping("/reviews")
    public List<ShopReview> reviews(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        return jdbc.query("""
                SELECT r.id, u.full_name AS author, r.rating, r.comment, r.created_at,
                       (r.order_id IS NOT NULL) AS purchased
                FROM reviews r JOIN users u ON u.id = r.user_id WHERE r.shop_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                """, (rs, row) -> new ShopReview(rs.getLong("id"), rs.getString("author"), rs.getInt("rating"),
                rs.getString("comment"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("purchased")), shopId);
    }

    @GetMapping("/traceability")
    public List<ShopTrace> traceability(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        return jdbc.query("""
                SELECT DISTINCT b.batch_code, b.product_name, b.origin, b.supplier, b.certified,
                       b.harvest_date, b.intake_date, b.certificates, b.storage_temperature,
                       b.storage_humidity, b.public_note
                FROM trace_batches b JOIN shop_products p ON p.batch_code = b.batch_code
                WHERE p.shop_id = ? ORDER BY b.batch_code DESC
                """, (rs, row) -> new ShopTrace(rs.getString("batch_code"), rs.getString("product_name"),
                rs.getString("origin"), rs.getString("supplier"), rs.getBoolean("certified"),
                rs.getDate("harvest_date") == null ? null : rs.getDate("harvest_date").toLocalDate(),
                rs.getDate("intake_date") == null ? null : rs.getDate("intake_date").toLocalDate(),
                rs.getString("certificates"), rs.getString("storage_temperature"), rs.getString("storage_humidity"),
                rs.getString("public_note")), shopId);
    }

    @GetMapping("/overview")
    public ShopOverview overview(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        ShopCounts counts = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM shop_products WHERE shop_id = ?) AS products,
                       (SELECT COUNT(*) FROM shop_products WHERE shop_id = ? AND stock_kg <= 0) AS sold_out,
                       (SELECT COUNT(*) FROM orders WHERE shop_id = ?) AS orders,
                       (SELECT COUNT(*) FROM orders WHERE shop_id = ? AND status = 'PENDING') AS pending_orders,
                       (SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE shop_id = ? AND status <> 'CANCELLED' AND created_at::date = CURRENT_DATE) AS order_value_today,
                       (SELECT COALESCE(AVG(rating), 0) FROM reviews WHERE shop_id = ?) AS rating,
                       (SELECT COUNT(*) FROM reviews WHERE shop_id = ?) AS review_count
                """, (rs, row) -> new ShopCounts(rs.getInt("products"), rs.getInt("sold_out"), rs.getInt("orders"),
                rs.getInt("pending_orders"), rs.getLong("order_value_today"), rs.getBigDecimal("rating"), rs.getInt("review_count")),
                shopId, shopId, shopId, shopId, shopId, shopId, shopId);
        List<DailyMetric> daily = jdbc.query("""
                SELECT d.day::date AS day, COALESCE((SELECT SUM(o.total_amount) FROM orders o
                    WHERE o.shop_id = ? AND o.status <> 'CANCELLED' AND o.created_at::date = d.day::date), 0) AS order_value
                FROM generate_series(CURRENT_DATE - INTERVAL '6 days', CURRENT_DATE, INTERVAL '1 day') d(day)
                ORDER BY d.day
                """, (rs, row) -> new DailyMetric(rs.getDate("day").toLocalDate(), rs.getLong("order_value")), shopId);
        List<TopProduct> topProducts = jdbc.query("""
                SELECT p.display_name AS name, COALESCE(SUM(oi.quantity_kg), 0) AS sold,
                       COALESCE(SUM(oi.line_total), 0) AS order_value
                FROM shop_products p LEFT JOIN order_items oi ON oi.shop_product_id = p.id
                    AND EXISTS (SELECT 1 FROM orders o WHERE o.id = oi.order_id AND o.status <> 'CANCELLED')
                WHERE p.shop_id = ? GROUP BY p.id, p.display_name ORDER BY sold DESC LIMIT 5
                """, (rs, row) -> new TopProduct(rs.getString("name"), rs.getBigDecimal("sold"), rs.getLong("order_value")), shopId);
        return new ShopOverview(counts.products(), counts.soldOut(), counts.orders(), counts.pendingOrders(),
                counts.orderValueToday(), counts.rating(), counts.reviewCount(), daily, topProducts);
    }

    private ShopProfile shop(long userId) {
        List<ShopProfile> shops = jdbc.query("""
                SELECT s.id, s.name, s.address, s.latitude, s.longitude, s.status,
                       s.description, s.rating_avg, s.review_count
                FROM shops s LEFT JOIN users u ON u.id = ?
                WHERE s.owner_user_id = ? OR s.id = u.shop_id
                ORDER BY s.id LIMIT 1
                """, (rs, row) -> new ShopProfile(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("status"),
                rs.getString("description"), rs.getBigDecimal("rating_avg"), rs.getInt("review_count")), userId, userId);
        if (shops.isEmpty()) throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        return shops.get(0);
    }

    private long shopId(long userId) {
        return shop(userId).id();
    }

    public record ShopProfile(long id, String name, String address, double latitude, double longitude,
                              String status, String description, BigDecimal rating, int reviewCount) {}
    public record ShopProduct(long id, long fruitId, String fruitName, String emoji, String category,
                              String displayName, long pricePerKg, BigDecimal stockKg, Integer aiScore,
                              String status, String batchCode) {}
    public record ShopOrderItem(String name, String emoji, BigDecimal quantityKg, long unitPrice, long lineTotal) {}
    public record ShopOrder(long id, String customer, long total, long subtotal, long shippingFee, String status,
                            String payment, String deliveryName, String deliveryPhone, String deliveryAddress,
                            String note, Instant createdAt, List<ShopOrderItem> items) {
        ShopOrder withItems(List<ShopOrderItem> orderItems) {
            return new ShopOrder(id, customer, total, subtotal, shippingFee, status, payment, deliveryName,
                    deliveryPhone, deliveryAddress, note, createdAt, orderItems);
        }
    }
    public record ShopReview(long id, String author, int rating, String comment, Instant createdAt, boolean purchased) {}
    public record ShopTrace(String batchCode, String product, String origin, String supplier, boolean certified,
                            LocalDate harvestDate, LocalDate intakeDate, String certificates,
                            String storageTemperature, String storageHumidity, String publicNote) {}
    public record DailyMetric(LocalDate date, long orderValue) {}
    public record TopProduct(String name, BigDecimal sold, long orderValue) {}
    public record ShopOverview(int products, int soldOut, int orders, int pendingOrders, long orderValueToday,
                               BigDecimal rating, int reviewCount, List<DailyMetric> daily, List<TopProduct> topProducts) {}
    private record ShopCounts(int products, int soldOut, int orders, int pendingOrders, long orderValueToday,
                              BigDecimal rating, int reviewCount) {}
}
