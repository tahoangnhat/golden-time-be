package com.goldentime.api.commerce;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products/{productId}")
public class ProductReviewController {
    private final JdbcTemplate jdbc;

    public ProductReviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/review-eligibility")
    public ReviewEligibility eligibility(@PathVariable long productId, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        verifyProductAndShop(productId);

        Boolean alreadyReviewed = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM product_reviews
                    WHERE user_id = ? AND shop_product_id = ?
                )
                """, Boolean.class, userId, productId);
        if (Boolean.TRUE.equals(alreadyReviewed)) {
            return new ReviewEligibility(false, true, null);
        }

        List<Long> eligibleOrders = jdbc.query("""
                SELECT o.id FROM orders o
                JOIN order_items oi ON oi.order_id = o.id
                WHERE o.user_id = ? AND oi.shop_product_id = ? AND o.status = 'DELIVERED'
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 1
                """, (rs, row) -> rs.getLong("id"), userId, productId);

        if (eligibleOrders.isEmpty()) {
            return new ReviewEligibility(false, false, null);
        }

        return new ReviewEligibility(true, false, eligibleOrders.get(0));
    }

    @GetMapping("/reviews")
    public ProductReviewSummary listReviews(@PathVariable long productId) {
        verifyProductAndShop(productId);

        ShopProductRating stats = jdbc.queryForObject("""
                SELECT COALESCE(rating_avg, 0.00) AS rating_avg, COALESCE(review_count, 0) AS review_count
                FROM shop_products WHERE id = ?
                """, (rs, row) -> new ShopProductRating(rs.getDouble("rating_avg"), rs.getInt("review_count")), productId);

        Map<Integer, Long> distMap = new LinkedHashMap<>();
        for (int s = 5; s >= 1; s--) {
            distMap.put(s, 0L);
        }
        jdbc.query("""
                SELECT rating, COUNT(*) AS count
                FROM product_reviews
                WHERE shop_product_id = ?
                GROUP BY rating
                """, rs -> {
            distMap.put(rs.getInt("rating"), rs.getLong("count"));
        }, productId);

        List<RatingDistribution> distribution = distMap.entrySet().stream()
                .map(e -> new RatingDistribution(e.getKey(), e.getValue()))
                .toList();

        List<ProductReviewItem> reviews = jdbc.query("""
                SELECT pr.id, pr.shop_product_id, pr.user_id, u.full_name AS author_name,
                       pr.rating, pr.comment, pr.created_at, pr.order_id
                FROM product_reviews pr
                JOIN users u ON u.id = pr.user_id
                WHERE pr.shop_product_id = ?
                ORDER BY pr.created_at DESC, pr.id DESC
                """, (rs, row) -> new ProductReviewItem(
                rs.getLong("id"),
                rs.getLong("shop_product_id"),
                rs.getLong("user_id"),
                rs.getString("author_name"),
                rs.getInt("rating"),
                rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant(),
                (Long) rs.getObject("order_id")), productId);

        double ratingAvg = stats != null ? stats.ratingAvg() : 0.0;
        int reviewCount = stats != null ? stats.reviewCount() : 0;

        return new ProductReviewSummary(ratingAvg, reviewCount, distribution, reviews);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public ProductReviewResponse createReview(
            @PathVariable long productId,
            @Valid @RequestBody CreateProductReviewRequest request,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        verifyProductAndShop(productId);

        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().trim();

        // 1. Pre-check: prevent review if already reviewed
        Boolean alreadyReviewed = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM product_reviews
                    WHERE user_id = ? AND shop_product_id = ?
                )
                """, Boolean.class, userId, productId);
        if (Boolean.TRUE.equals(alreadyReviewed)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bạn đã đánh giá sản phẩm này rồi.");
        }

        // 2. Eligibility verification: buyer must have a DELIVERED order containing this product
        List<Long> eligibleOrders = jdbc.query("""
                SELECT o.id FROM orders o
                JOIN order_items oi ON oi.order_id = o.id
                WHERE o.user_id = ? AND oi.shop_product_id = ? AND o.status = 'DELIVERED'
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 1
                """, (rs, row) -> rs.getLong("id"), userId, productId);

        if (eligibleOrders.isEmpty()) {
            throw ApiException.forbidden("Chỉ người mua đã nhận hàng (đơn hàng đã giao thành công) mới được đánh giá sản phẩm.");
        }
        Long orderId = eligibleOrders.get(0);

        // 3. Insert review - database unique constraint acts as the final barrier
        Long reviewId;
        try {
            reviewId = jdbc.queryForObject("""
                    INSERT INTO product_reviews (user_id, shop_product_id, order_id, rating, comment)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, userId, productId, orderId, request.rating(), comment);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Bạn đã đánh giá sản phẩm này rồi.");
        }

        // 4. Atomic recalculation of product rating statistics
        jdbc.update("""
                UPDATE shop_products SET
                    rating_avg = (SELECT COALESCE(ROUND(AVG(rating)::numeric, 2), 0.00) FROM product_reviews WHERE shop_product_id = ?),
                    review_count = (SELECT COUNT(*) FROM product_reviews WHERE shop_product_id = ?)
                WHERE id = ?
                """, productId, productId, productId);

        return byId(reviewId);
    }

    private ProductReviewResponse byId(long id) {
        return jdbc.queryForObject("""
                SELECT pr.id, pr.shop_product_id, pr.user_id, u.full_name AS author_name,
                       pr.rating, pr.comment, pr.created_at, pr.order_id
                FROM product_reviews pr
                JOIN users u ON u.id = pr.user_id
                WHERE pr.id = ?
                """, (rs, row) -> new ProductReviewResponse(
                rs.getLong("id"),
                rs.getLong("shop_product_id"),
                rs.getLong("user_id"),
                rs.getString("author_name"),
                rs.getInt("rating"),
                rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant(),
                (Long) rs.getObject("order_id")), id);
    }

    private void verifyProductAndShop(long productId) {
        List<String> shopStatuses = jdbc.query("""
                SELECT s.status FROM shop_products p
                JOIN shops s ON s.id = p.shop_id
                WHERE p.id = ?
                """, (rs, row) -> rs.getString("status"), productId);
        if (shopStatuses.isEmpty()) {
            throw ApiException.notFound("Không tìm thấy sản phẩm.");
        }
        if ("SUSPENDED".equals(shopStatuses.get(0))) {
            throw ApiException.forbidden("Cửa hàng hiện đang bị tạm ngưng hoạt động.");
        }
    }

    public record ReviewEligibility(boolean eligible, boolean alreadyReviewed, Long eligibleOrderId) {}

    public record CreateProductReviewRequest(
            @NotNull(message = "Số sao đánh giá không được để trống.")
            @Min(value = 1, message = "Số sao tối thiểu là 1.")
            @Max(value = 5, message = "Số sao tối đa là 5.")
            Integer rating,
            @Size(max = 2000, message = "Bình luận tối đa 2000 ký tự.")
            String comment) {}

    public record ProductReviewResponse(
            long id,
            long productId,
            long userId,
            String authorName,
            int rating,
            String comment,
            Instant createdAt,
            Long orderId) {}

    private record ShopProductRating(double ratingAvg, int reviewCount) {}

    public record RatingDistribution(int star, long count) {}

    public record ProductReviewItem(
            long id,
            long productId,
            long userId,
            String authorName,
            int rating,
            String comment,
            Instant createdAt,
            Long orderId) {}

    public record ProductReviewSummary(
            double ratingAvg,
            int reviewCount,
            List<RatingDistribution> distribution,
            List<ProductReviewItem> reviews) {}
}
