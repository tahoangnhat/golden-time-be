package com.goldentime.api.partner;

import com.goldentime.api.articles.ArticleImageStorage;
import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Partner views sourced from the authenticated shop owner's records. */
@RestController
@RequestMapping("/api/shop")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class PartnerDataController {
    private static final String SHOP_PRODUCT_SELECT = """
            SELECT p.id, f.id AS fruit_id, COALESCE(f.name, p.display_name) AS fruit_name,
                   COALESCE(f.emoji, '🍎') AS emoji,
                   p.product_category AS product_category,
                   (p.product_category = 'SEASONAL') AS is_seasonal,
                   (p.product_category = 'PREPARED') AS is_prepared,
                   p.display_name, p.price_per_kg, p.stock_kg, p.ai_score, p.status, p.batch_code, p.image_url
            FROM shop_products p LEFT JOIN fruits f ON f.id = p.fruit_id
            """;
    private static final RowMapper<ShopProduct> SHOP_PRODUCT_MAPPER = (rs, row) -> new ShopProduct(
            rs.getLong("id"), (Long) rs.getObject("fruit_id"), rs.getString("fruit_name"), rs.getString("emoji"),
            rs.getString("product_category"), rs.getBoolean("is_seasonal"), rs.getBoolean("is_prepared"),
            rs.getString("display_name"), rs.getLong("price_per_kg"),
            rs.getBigDecimal("stock_kg"), (Integer) rs.getObject("ai_score"), rs.getString("status"),
            rs.getString("batch_code"), rs.getString("image_url"));

    private final JdbcTemplate jdbc;
    private final ArticleImageStorage imageStorage;

    public PartnerDataController(JdbcTemplate jdbc, ArticleImageStorage imageStorage) {
        this.jdbc = jdbc;
        this.imageStorage = imageStorage;
    }

    @GetMapping("/profile")
    public ShopProfile profile(Authentication authentication) {
        return shop(CurrentUser.id(authentication));
    }

    @PutMapping("/profile")
    public ShopProfile updateProfile(Authentication authentication, @Valid @RequestBody ShopLocationUpdate request) {
        if (!validCoordinates(request.latitude(), request.longitude())) {
            throw ApiException.badRequest("Vị trí cửa hàng không hợp lệ.");
        }
        String address = request.address().trim();
        if (address.length() < 5) throw ApiException.badRequest("Địa chỉ cửa hàng cần dài ít nhất 5 ký tự.");
        String placeId = request.googlePlaceId() == null || request.googlePlaceId().isBlank()
                ? null : request.googlePlaceId().trim();
        if (placeId != null && placeId.length() > 255) throw ApiException.badRequest("Mã địa điểm Google Maps không hợp lệ.");

        long userId = CurrentUser.id(authentication);
        long shopId = shopId(userId);
        jdbc.update("""
                UPDATE shops SET name = COALESCE(NULLIF(?, ''), name), address = ?, latitude = ?, longitude = ?,
                    google_place_id = ?, business_email = CASE WHEN ? IS NULL THEN business_email ELSE NULLIF(?, '') END,
                    business_phone = CASE WHEN ? IS NULL THEN business_phone ELSE NULLIF(?, '') END,
                    tax_code = CASE WHEN ? IS NULL THEN tax_code ELSE NULLIF(?, '') END,
                    representative_name = CASE WHEN ? IS NULL THEN representative_name ELSE NULLIF(?, '') END,
                    representative_phone = CASE WHEN ? IS NULL THEN representative_phone ELSE NULLIF(?, '') END,
                    logo_url = CASE WHEN ? IS NULL THEN logo_url ELSE NULLIF(?, '') END
                WHERE id = ?
                """, request.name() == null ? null : request.name().trim(), address, request.latitude(), request.longitude(), placeId,
                request.businessEmail(), request.businessEmail(), request.businessPhone(), request.businessPhone(),
                request.taxCode(), request.taxCode(), request.representativeName(), request.representativeName(),
                request.representativePhone(), request.representativePhone(), request.logoUrl(), request.logoUrl(), shopId);
        if (request.businessPhone() != null) {
            jdbc.update("UPDATE users SET phone = NULLIF(?, '') WHERE shop_id = ? AND role = 'SHOP_OWNER'",
                    request.businessPhone().trim(), shopId);
        }
        return shop(userId);
    }

    @PostMapping(value = "/profile/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadBusinessLogo(Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        shopId(CurrentUser.id(authentication));
        return imageStorage.saveBusinessLogo(file);
    }

    @GetMapping("/products")
    public List<ShopProduct> products(Authentication authentication) {
        long shopId = shopId(CurrentUser.id(authentication));
        return jdbc.query(SHOP_PRODUCT_SELECT + " WHERE p.shop_id = ? ORDER BY p.created_at DESC, p.id DESC",
                SHOP_PRODUCT_MAPPER, shopId);
    }

    @PostMapping(value = "/products/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadProductImage(Authentication authentication,
                                                                @RequestPart("file") MultipartFile file) {
        shopId(CurrentUser.id(authentication));
        return imageStorage.saveProductImage(file);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ShopProduct createProduct(Authentication authentication, @Valid @RequestBody ShopProductWrite request) {
        long shopId = shopId(CurrentUser.id(authentication));
        validateProductCategory(request.category());
        if (request.fruitId() != null) validateFruit(request.fruitId());
        String imageUrl = validateProductImageUrl(request.imageUrl());
        long productId = jdbc.queryForObject("""
                INSERT INTO shop_products (shop_id, fruit_id, product_category, is_seasonal, is_prepared, display_name, price_per_kg, stock_kg, status, image_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, shopId, request.fruitId(), request.category(),
                "SEASONAL".equals(request.category()), "PREPARED".equals(request.category()), request.displayName().trim(), request.pricePerKg(),
                request.stockKg(), productStatus(request.onSale(), request.stockKg()), imageUrl);
        return shopProduct(shopId, productId);
    }

    @PutMapping("/products/{productId}")
    public ShopProduct updateProduct(Authentication authentication, @PathVariable long productId,
                                     @Valid @RequestBody ShopProductWrite request) {
        long shopId = shopId(CurrentUser.id(authentication));
        validateProductCategory(request.category());
        if (request.fruitId() != null) validateFruit(request.fruitId());
        String imageUrl = validateProductImageUrl(request.imageUrl());
        int updated = jdbc.update("""
                UPDATE shop_products
                SET fruit_id = ?, product_category = ?, is_seasonal = ?, is_prepared = ?, display_name = ?, price_per_kg = ?, stock_kg = ?, status = ?, image_url = ?
                WHERE id = ? AND shop_id = ?
                """, request.fruitId(), request.category(), "SEASONAL".equals(request.category()), "PREPARED".equals(request.category()),
                request.displayName().trim(), request.pricePerKg(), request.stockKg(),
                productStatus(request.onSale(), request.stockKg()), imageUrl, productId, shopId);
        if (updated != 1) throw ApiException.notFound("Không tìm thấy sản phẩm của cửa hàng.");
        return shopProduct(shopId, productId);
    }

    private void validateFruit(long fruitId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM fruits WHERE id = ?)", Boolean.class, fruitId);
        if (!Boolean.TRUE.equals(exists)) throw ApiException.notFound("Không tìm thấy loại trái cây.");
    }

    private void validateProductCategory(String category) {
        if (!List.of("LOCAL", "IMPORTED", "SEASONAL", "PREPARED").contains(category)) {
            throw ApiException.badRequest("Danh mục sản phẩm không hợp lệ.");
        }
    }

    private String validateProductImageUrl(String imageUrl) {
        String value = imageUrl.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("Địa chỉ ảnh sản phẩm không hợp lệ.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw ApiException.badRequest("Ảnh sản phẩm cần có địa chỉ HTTPS công khai.");
        }
        return value;
    }

    private ShopProduct shopProduct(long shopId, long productId) {
        List<ShopProduct> products = jdbc.query(SHOP_PRODUCT_SELECT + " WHERE p.shop_id = ? AND p.id = ?",
                SHOP_PRODUCT_MAPPER, shopId, productId);
        if (products.isEmpty()) throw ApiException.notFound("Không tìm thấy sản phẩm của cửa hàng.");
        return products.get(0);
    }

    private static String productStatus(boolean onSale, BigDecimal stockKg) {
        if (!onSale) return "HIDDEN";
        return stockKg.signum() > 0 ? "ON_SALE" : "OUT_OF_STOCK";
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
                        SELECT p.display_name, COALESCE(f.emoji, '🍎') AS emoji, oi.quantity_kg, oi.unit_price, oi.line_total
                        FROM order_items oi JOIN shop_products p ON p.id = oi.shop_product_id
                        LEFT JOIN fruits f ON f.id = p.fruit_id WHERE oi.order_id = ? ORDER BY oi.id
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
                SELECT s.id, s.name, s.address, s.latitude, s.longitude, s.google_place_id, s.status,
                       s.description, s.rating_avg, s.review_count, s.business_email, s.business_phone,
                       s.tax_code, s.representative_name, s.representative_phone, s.logo_url
                FROM shops s LEFT JOIN users u ON u.id = ?
                WHERE s.owner_user_id = ? OR s.id = u.shop_id
                ORDER BY s.id LIMIT 1
                """, (rs, row) -> new ShopProfile(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("google_place_id"), rs.getString("status"),
                rs.getString("description"), rs.getBigDecimal("rating_avg"), rs.getInt("review_count"),
                rs.getString("business_email"), rs.getString("business_phone"), rs.getString("tax_code"),
                rs.getString("representative_name"), rs.getString("representative_phone"), rs.getString("logo_url")), userId, userId);
        if (shops.isEmpty()) throw ApiException.forbidden("Tài khoản chưa được liên kết với cửa hàng.");
        return shops.get(0);
    }

    private long shopId(long userId) {
        return shop(userId).id();
    }

    private static boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    public record ShopProfile(long id, String name, String address, double latitude, double longitude,
                              String googlePlaceId, String status, String description, BigDecimal rating, int reviewCount,
                              String businessEmail, String businessPhone, String taxCode,
                              String representativeName, String representativePhone, String logoUrl) {}
    public record ShopLocationUpdate(@NotBlank @Size(max = 512) String address, @NotNull Double latitude,
                                    @NotNull Double longitude, @Size(max = 255) String googlePlaceId,
                                    @Size(min = 2, max = 255) String name,
                                    @Size(max = 255) String businessEmail, @Size(max = 32) String businessPhone,
                                    @Size(max = 64) String taxCode, @Size(max = 255) String representativeName,
                                    @Size(max = 32) String representativePhone, @Size(max = 1024) String logoUrl) {}
    public record ShopProductWrite(Long fruitId,
                                   @NotBlank @Size(max = 32) String category,
                                   @NotBlank @Size(max = 255) String displayName,
                                   @NotNull @Positive @Digits(integer = 12, fraction = 0) Long pricePerKg,
                                   @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal stockKg,
                                   @NotNull Boolean onSale,
                                   @NotBlank @Size(max = 1024) String imageUrl) {}
    public record ShopProduct(long id, Long fruitId, String fruitName, String emoji, String category,
                              boolean seasonal, boolean prepared,
                              String displayName, long pricePerKg, BigDecimal stockKg, Integer aiScore,
                              String status, String batchCode, String imageUrl) {}
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
