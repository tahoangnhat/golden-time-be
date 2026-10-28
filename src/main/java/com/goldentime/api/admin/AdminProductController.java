package com.goldentime.api.admin;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final JdbcTemplate jdbc;

    public AdminProductController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/{productId}/decision")
    @Transactional
    public ProductDecision decide(@PathVariable long productId, @Valid @RequestBody ProductReviewDecision request,
                                  Authentication authentication) {
        ProductForReview product = jdbc.query("""
                SELECT approval_status, stock_kg
                FROM shop_products WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ProductForReview(rs.getString("approval_status"), rs.getBigDecimal("stock_kg")), productId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Không tìm thấy sản phẩm."));
        if (!"PENDING_REVIEW".equals(product.approvalStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Sản phẩm không còn chờ duyệt.");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (!request.approved() && note.isBlank()) {
            throw ApiException.badRequest("Hãy ghi rõ thông tin Business cần bổ sung.");
        }
        String approvalStatus = request.approved() ? "APPROVED" : "NEEDS_CORRECTION";
        String status = request.approved()
                ? (product.stockKg().signum() > 0 ? "ON_SALE" : "OUT_OF_STOCK")
                : "HIDDEN";
        String reviewNote = request.approved() ? "" : note;
        jdbc.update("""
                UPDATE shop_products
                SET approval_status = ?, status = ?, review_note = NULLIF(?, ''), reviewed_by = ?, reviewed_at = NOW()
                WHERE id = ?
                """, approvalStatus, status, reviewNote, CurrentUser.id(authentication), productId);
        return new ProductDecision(productId, approvalStatus, status, reviewNote.isBlank() ? null : reviewNote);
    }

    public record ProductReviewDecision(@NotNull Boolean approved, @Size(max = 4000) String note) {}
    public record ProductDecision(long id, String approvalStatus, String status, String reviewNote) {}
    private record ProductForReview(String approvalStatus, java.math.BigDecimal stockKg) {}
}
