package com.goldentime.api.auth;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.scan.UploadStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    private final UploadStorage uploadStorage;

    public AccountDeletionService(JdbcTemplate jdbc, UploadStorage uploadStorage) {
        this.jdbc = jdbc;
        this.uploadStorage = uploadStorage;
    }

    @Transactional
    public void deleteCustomerAccount(long userId) {
        List<String> imageFiles = jdbc.query(
                "SELECT image_path FROM ai_scans WHERE user_id = ? AND image_path IS NOT NULL",
                (rs, row) -> rs.getString(1), userId);

        List<Long> productIds = jdbc.query(
                "SELECT DISTINCT shop_product_id FROM product_reviews WHERE user_id = ?",
                (rs, row) -> rs.getLong(1), userId);
        List<Long> affectedShopIds = jdbc.query("""
                SELECT shop_id FROM (
                    SELECT DISTINCT p.shop_id FROM product_reviews pr
                    JOIN shop_products p ON p.id = pr.shop_product_id
                    WHERE pr.user_id = ?
                    UNION
                    SELECT DISTINCT shop_id FROM orders WHERE user_id = ? AND shop_rating IS NOT NULL
                ) affected
                """, (rs, row) -> rs.getLong(1), userId, userId);

        // Remove user-authored content before its scan history, which may be referenced by a review.
        jdbc.update("DELETE FROM reviews WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM product_reviews WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ai_scans WHERE user_id = ?", userId);

        for (Long productId : productIds) {
            jdbc.update("""
                    UPDATE shop_products SET
                        rating_avg = (SELECT COALESCE(ROUND(AVG(rating)::numeric, 2), 0.00)
                                      FROM product_reviews WHERE shop_product_id = ?),
                        review_count = (SELECT COUNT(*) FROM product_reviews WHERE shop_product_id = ?)
                    WHERE id = ?
                    """, productId, productId, productId);
        }
        for (Long shopId : affectedShopIds) {
            // Shop summaries for product reviews are derived from the remaining product reviews.
            jdbc.update("""
                    UPDATE shops SET
                        rating_avg = (SELECT COALESCE(ROUND(AVG(pr.rating)::numeric, 2), 0.00)
                                      FROM product_reviews pr
                                      JOIN shop_products p ON p.id = pr.shop_product_id
                                      WHERE p.shop_id = ?),
                        review_count = (SELECT COUNT(*)
                                        FROM product_reviews pr
                                        JOIN shop_products p ON p.id = pr.shop_product_id
                                        WHERE p.shop_id = ?)
                    WHERE id = ?
                    """, shopId, shopId, shopId);
        }

        // Keep order totals and item lines for reconciliation, but remove recipient details and ratings.
        jdbc.update("""
                UPDATE orders SET delivery_name = NULL, delivery_phone = NULL, delivery_address = NULL,
                    delivery_province_name = NULL, delivery_ward_name = NULL, note = NULL, shop_rating = NULL,
                    user_id = NULL
                WHERE user_id = ?
                """, userId);

        jdbc.update("DELETE FROM premium_health_profiles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM premium_memberships WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM premium_payments WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM google_play_premium_purchases WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM buyer_vouchers WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM account_deletion_requests WHERE user_id = ?", userId);

        // Clear optional audit references so the account row can be permanently removed.
        jdbc.update("UPDATE shops SET owner_user_id = NULL WHERE owner_user_id = ?", userId);
        jdbc.update("UPDATE business_applications SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE shop_wallet_transactions SET created_by = NULL WHERE created_by = ?", userId);
        jdbc.update("UPDATE shop_withdrawal_requests SET requested_by = NULL, reviewed_by = NULL WHERE requested_by = ? OR reviewed_by = ?", userId, userId);
        jdbc.update("UPDATE shop_products SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE farms SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE trace_batches SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE trace_batch_reviews SET reviewed_by = NULL WHERE reviewed_by = ?", userId);
        jdbc.update("UPDATE vouchers SET created_by = NULL WHERE created_by = ?", userId);
        int updated = jdbc.update("""
                DELETE FROM users
                WHERE id = ? AND role = 'USER' AND enabled = TRUE
                """, userId);
        if (updated == 0) throw ApiException.notFound("Không tìm thấy tài khoản khách hàng đang hoạt động.");

        if (!imageFiles.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    imageFiles.forEach(uploadStorage::delete);
                }
            });
        }
    }
}
