package com.goldentime.api.commerce.payment;

import com.goldentime.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;

    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void recordPaidOrder(long orderId, long shopId, String externalReference, Long createdBy) {
        List<WalletOrder> orders = jdbc.query("""
                SELECT o.subtotal_amount FROM orders o
                WHERE o.id = ? AND o.shop_id = ? AND o.payment_status = 'PAID'
                """, (rs, row) -> new WalletOrder(rs.getLong(1)), orderId, shopId);
        if (orders.isEmpty()) return;

        long gross = orders.get(0).subtotal();
        BigDecimal rate = effectiveCommissionRate(shopId);
        long commission = BigDecimal.valueOf(gross).multiply(rate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        jdbc.update("""
                INSERT INTO shop_wallet_transactions
                    (shop_id, order_id, entry_type, amount, gross_amount, commission_rate, commission_amount,
                     external_reference, description, created_by)
                VALUES (?, ?, 'ORDER_SALE', ?, ?, ?, ?, ?, 'Tiền hàng đã thu qua sàn', ?)
                ON CONFLICT (order_id, entry_type) DO NOTHING
                """, shopId, orderId, gross, gross, rate, commission, externalReference, createdBy);
        if (commission > 0) {
            jdbc.update("""
                    INSERT INTO shop_wallet_transactions
                        (shop_id, order_id, entry_type, amount, gross_amount, commission_rate, commission_amount,
                         external_reference, description, created_by)
                    VALUES (?, ?, 'COMMISSION_FEE', ?, ?, ?, ?, ?, 'Hoa hồng sàn', ?)
                    ON CONFLICT (order_id, entry_type) DO NOTHING
                    """, shopId, orderId, -commission, gross, rate, commission, externalReference, createdBy);
        }
    }

    @Transactional
    public void updateCommission(long shopId, String tier, BigDecimal customRate) {
        if (!List.of("NEW_SHOP", "STANDARD", "LARGE_PARTNER", "CUSTOM").contains(tier)) {
            throw ApiException.badRequest("Loại hoa hồng không hợp lệ.");
        }
        if ("CUSTOM".equals(tier)) {
            if (customRate == null || customRate.compareTo(BigDecimal.ZERO) < 0 || customRate.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("Hoa hồng tùy chỉnh phải từ 0% đến 100%.");
            }
        } else if (customRate != null) {
            throw ApiException.badRequest("Chỉ nhập tỷ lệ riêng khi chọn hoa hồng tùy chỉnh.");
        }
        int updated = jdbc.update("""
                UPDATE shops SET commission_tier = ?, custom_commission_rate = ? WHERE id = ?
                """, tier, customRate, shopId);
        if (updated != 1) throw ApiException.notFound("Không tìm thấy cửa hàng.");
    }

    @Transactional
    public void recordPayout(long shopId, long amount, String reference, String note, long adminUserId) {
        if (amount <= 0) throw ApiException.badRequest("Số tiền thanh toán phải lớn hơn 0.");
        List<Long> shopRows = jdbc.query("SELECT id FROM shops WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), shopId);
        if (shopRows.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        long balance = balance(shopId);
        if (amount > balance) throw new ApiException(HttpStatus.CONFLICT, "Số tiền thanh toán vượt quá số dư có thể chi.");
        jdbc.update("""
                INSERT INTO shop_wallet_transactions
                    (shop_id, entry_type, amount, external_reference, description, created_by)
                VALUES (?, 'PAYOUT', ?, ?, ?, ?)
                """, shopId, -amount, blankToNull(reference), blankToNull(note), adminUserId);
    }

    @Transactional
    public void markCodCollected(long orderId, String reference, long adminUserId) {
        List<OrderForCod> orders = jdbc.query("""
                SELECT shop_id, payment_status FROM orders
                WHERE id = ? AND payment_method = 'COD' AND status <> 'CANCELLED'
                FOR UPDATE
                """, (rs, row) -> new OrderForCod(rs.getLong("shop_id"), rs.getString("payment_status")), orderId);
        if (orders.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn COD có thể đối soát.");
        OrderForCod order = orders.get(0);
        if (!"UNPAID".equals(order.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Đơn COD đã được đối soát hoặc không còn chờ thu tiền.");
        }
        int updated = jdbc.update("""
                UPDATE orders SET payment_status = 'PAID', paid_at = NOW()
                WHERE id = ? AND payment_method = 'COD' AND payment_status = 'UNPAID'
                """, orderId);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "Trạng thái thanh toán COD vừa thay đổi.");
        recordPaidOrder(orderId, order.shopId(), blankToNull(reference), adminUserId);
    }

    public List<WalletSummary> adminSummaries() {
        return jdbc.query("""
                SELECT s.id AS shop_id, s.name AS shop_name, s.commission_tier, s.custom_commission_rate,
                       s.approved_at,
                       CASE
                         WHEN s.commission_tier = 'NEW_SHOP' AND s.approved_at + INTERVAL '1 month' > NOW() THEN 0
                         WHEN s.commission_tier = 'NEW_SHOP' THEN 5
                         WHEN s.commission_tier = 'LARGE_PARTNER' THEN 3
                         WHEN s.commission_tier = 'CUSTOM' THEN COALESCE(s.custom_commission_rate, 5)
                         ELSE 5
                       END AS effective_rate,
                       COALESCE(SUM(w.amount), 0) AS balance,
                       COALESCE(SUM(w.amount) FILTER (WHERE w.entry_type = 'ORDER_SALE'), 0) AS gross_sales,
                       COALESCE(SUM(w.commission_amount) FILTER (WHERE w.entry_type = 'COMMISSION_FEE'), 0) AS commissions,
                       COALESCE(-SUM(w.amount) FILTER (WHERE w.entry_type = 'PAYOUT'), 0) AS payouts
                FROM shops s LEFT JOIN shop_wallet_transactions w ON w.shop_id = s.id
                GROUP BY s.id ORDER BY s.name, s.id
                """, WalletService::summaryRow);
    }

    public WalletView shopWallet(long shopId) {
        return walletView(shopId);
    }

    public WalletView adminWallet(long shopId) {
        return walletView(shopId);
    }

    public List<WalletTransaction> transactions(long shopId) {
        return jdbc.query("""
                SELECT id, order_id, entry_type, amount, gross_amount, commission_rate, commission_amount,
                       external_reference, description, created_at
                FROM shop_wallet_transactions WHERE shop_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 200
                """, (rs, row) -> new WalletTransaction(rs.getLong("id"), (Long) rs.getObject("order_id"),
                rs.getString("entry_type"), rs.getLong("amount"), (Long) rs.getObject("gross_amount"),
                rs.getBigDecimal("commission_rate"), (Long) rs.getObject("commission_amount"),
                rs.getString("external_reference"), rs.getString("description"), rs.getTimestamp("created_at").toInstant()), shopId);
    }

    private WalletView walletView(long shopId) {
        List<WalletSummary> rows = jdbc.query("""
                SELECT s.id AS shop_id, s.name AS shop_name, s.commission_tier, s.custom_commission_rate,
                       s.approved_at,
                       CASE
                         WHEN s.commission_tier = 'NEW_SHOP' AND s.approved_at + INTERVAL '1 month' > NOW() THEN 0
                         WHEN s.commission_tier = 'NEW_SHOP' THEN 5
                         WHEN s.commission_tier = 'LARGE_PARTNER' THEN 3
                         WHEN s.commission_tier = 'CUSTOM' THEN COALESCE(s.custom_commission_rate, 5)
                         ELSE 5
                       END AS effective_rate,
                       COALESCE(SUM(w.amount), 0) AS balance,
                       COALESCE(SUM(w.amount) FILTER (WHERE w.entry_type = 'ORDER_SALE'), 0) AS gross_sales,
                       COALESCE(SUM(w.commission_amount) FILTER (WHERE w.entry_type = 'COMMISSION_FEE'), 0) AS commissions,
                       COALESCE(-SUM(w.amount) FILTER (WHERE w.entry_type = 'PAYOUT'), 0) AS payouts
                FROM shops s LEFT JOIN shop_wallet_transactions w ON w.shop_id = s.id
                WHERE s.id = ? GROUP BY s.id
                """, WalletService::summaryRow, shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        WalletSummary summary = rows.get(0);
        return new WalletView(summary.shopId(), summary.shopName(), summary.commissionTier(), summary.commissionRate(),
                summary.approvedAt(), summary.balance(), summary.grossSales(), summary.commissions(), summary.payouts());
    }

    private long balance(long shopId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM shop_wallet_transactions WHERE shop_id = ?", Long.class, shopId);
        return value == null ? 0 : value;
    }

    private BigDecimal effectiveCommissionRate(long shopId) {
        List<CommissionSetting> settings = jdbc.query("""
                SELECT commission_tier, custom_commission_rate, approved_at FROM shops WHERE id = ? FOR UPDATE
                """, (rs, row) -> new CommissionSetting(rs.getString("commission_tier"), rs.getBigDecimal("custom_commission_rate"),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant()), shopId);
        if (settings.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        CommissionSetting setting = settings.get(0);
        return switch (setting.tier()) {
            case "NEW_SHOP" -> setting.approvedAt() != null
                    && setting.approvedAt().atZone(ZoneOffset.UTC).plusMonths(1).toInstant().isAfter(Instant.now())
                    ? BigDecimal.ZERO : BigDecimal.valueOf(5);
            case "LARGE_PARTNER" -> BigDecimal.valueOf(3);
            case "CUSTOM" -> setting.customRate() == null ? BigDecimal.valueOf(5) : setting.customRate();
            default -> BigDecimal.valueOf(5);
        };
    }

    private static WalletSummary summaryRow(ResultSet rs, int row) throws SQLException {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new WalletSummary(rs.getLong("shop_id"), rs.getString("shop_name"), rs.getString("commission_tier"),
                rs.getBigDecimal("effective_rate"), approvedAt == null ? null : approvedAt.toInstant(),
                rs.getLong("balance"), rs.getLong("gross_sales"), rs.getLong("commissions"), rs.getLong("payouts"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record WalletOrder(long subtotal) {}
    private record OrderForCod(long shopId, String paymentStatus) {}
    private record CommissionSetting(String tier, BigDecimal customRate, Instant approvedAt) {}
    public record WalletSummary(long shopId, String shopName, String commissionTier, BigDecimal commissionRate,
                                Instant approvedAt, long balance, long grossSales, long commissions, long payouts) {}
    public record WalletView(long shopId, String shopName, String commissionTier, BigDecimal commissionRate,
                            Instant approvedAt, long balance, long grossSales, long commissions, long payouts) {}
    public record WalletTransaction(long id, Long orderId, String entryType, long amount, Long grossAmount,
                                    BigDecimal commissionRate, Long commissionAmount, String externalReference,
                                    String description, Instant createdAt) {}
}
