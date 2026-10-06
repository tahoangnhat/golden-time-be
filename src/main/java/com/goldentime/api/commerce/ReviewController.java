package com.goldentime.api.commerce;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
public class ReviewController {
    private final JdbcTemplate jdbc;

    public ReviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/shops/{shopId}/reviews")
    public List<Review> list(@PathVariable long shopId) {
        return jdbc.query("""
                SELECT r.id, r.shop_id, s.name AS shop_name, r.user_id, u.full_name AS author,
                       r.rating, r.comment, r.created_at,
                       (r.order_id IS NOT NULL) AS purchased
                FROM reviews r
                JOIN shops s ON s.id = r.shop_id
                JOIN users u ON u.id = r.user_id
                WHERE r.shop_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                """, (rs, row) -> new Review(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("shop_name"), rs.getLong("user_id"), rs.getString("author"),
                rs.getInt("rating"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("purchased")), shopId);
    }

    @PostMapping("/api/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    public Review create(@Valid @RequestBody CreateReviewRequest request, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        if ((request.orderId() == null) == (request.scanId() == null)) {
            throw ApiException.badRequest("Cần cung cấp một mã đơn hàng hoặc mã lần quét để xác nhận đánh giá.");
        }

        boolean purchased = request.orderId() != null;
        if (purchased) {
            Integer eligible = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM orders
                    WHERE id = ? AND user_id = ? AND shop_id = ? AND status <> 'CANCELLED'
                    """, Integer.class, request.orderId(), userId, request.shopId());
            if (eligible == null || eligible == 0) throw ApiException.forbidden("Chỉ có thể đánh giá cửa hàng trong đơn hàng của bạn.");
        } else {
            Integer eligible = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ai_scans
                    WHERE id = ? AND user_id = ? AND (shop_id IS NULL OR shop_id = ?)
                    """, Integer.class, request.scanId(), userId, request.shopId());
            if (eligible == null || eligible == 0) throw ApiException.forbidden("Chỉ có thể đánh giá sau khi quét sản phẩm tại cửa hàng này.");
        }

        Long reviewId;
        try {
            reviewId = jdbc.queryForObject("""
                    INSERT INTO reviews (user_id, shop_id, order_id, scan_id, rating, comment)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """, Long.class, userId, request.shopId(), request.orderId(), request.scanId(),
                    request.rating(), request.comment().trim());
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw ApiException.badRequest("Mã đơn hàng hoặc lần quét này đã được dùng để đánh giá.");
        }

        jdbc.update("""
                UPDATE shops SET
                    rating_avg = (SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE shop_id = ?),
                    review_count = (SELECT COUNT(*) FROM reviews WHERE shop_id = ?)
                WHERE id = ?
                """, request.shopId(), request.shopId(), request.shopId());
        return byId(reviewId);
    }

    private Review byId(long id) {
        return jdbc.queryForObject("""
                SELECT r.id, r.shop_id, s.name AS shop_name, r.user_id, u.full_name AS author,
                       r.rating, r.comment, r.created_at, (r.order_id IS NOT NULL) AS purchased
                FROM reviews r JOIN shops s ON s.id = r.shop_id JOIN users u ON u.id = r.user_id
                WHERE r.id = ?
                """, (rs, row) -> new Review(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("shop_name"), rs.getLong("user_id"), rs.getString("author"),
                rs.getInt("rating"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("purchased")), id);
    }

    public record CreateReviewRequest(@NotNull Long shopId,
                                      Long orderId,
                                      Long scanId,
                                      @Min(1) @Max(5) int rating,
                                      @NotBlank @Size(max = 2000) String comment) {}

    public record Review(long id, long shopId, String shopName, long userId, String author,
                         int rating, String comment, Instant createdAt, boolean purchased) {}
}
