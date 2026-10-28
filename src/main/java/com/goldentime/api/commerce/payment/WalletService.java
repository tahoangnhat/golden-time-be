package com.goldentime.api.commerce.payment;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.wallet.account-encryption-key:}")
    private String accountEncryptionKey;

    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean recordPaidOrder(long orderId, long shopId, String externalReference, Long createdBy) {
        List<WalletOrder> orders = jdbc.query("""
                SELECT o.subtotal_amount FROM orders o
                WHERE o.id = ? AND o.shop_id = ? AND o.payment_status = 'PAID' AND o.status = 'DELIVERED'
                """, (rs, row) -> new WalletOrder(rs.getLong(1)), orderId, shopId);
        if (orders.isEmpty()) return false;

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
        return true;
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
    public ShopWithdrawalRequestView createWithdrawalRequest(long shopId, long userId, long amount, String bankCode,
                                                             String bankName, String accountNumber, String accountName) {
        if (amount <= 0) throw ApiException.badRequest("Số tiền rút phải lớn hơn 0.");
        lockShop(shopId);
        long available = balance(shopId) - reservedAmount(shopId);
        if (amount > available) throw new ApiException(HttpStatus.CONFLICT, "Số tiền rút vượt quá số dư khả dụng.");
        Long id = jdbc.queryForObject("""
                INSERT INTO shop_withdrawal_requests
                    (shop_id, amount, bank_code, bank_name, account_number_encrypted, account_number_last4, account_name_encrypted, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, shopId, amount, requiredText(bankCode), requiredText(bankName),
                encryptAccountNumber(requiredText(accountNumber).replaceAll("\\s+", "")),
                lastFour(requiredText(accountNumber).replaceAll("\\s+", "")), encryptAccountNumber(requiredText(accountName)), userId);
        return shopWithdrawalRequest(id, shopId);
    }

    public List<ShopWithdrawalRequestView> shopWithdrawalRequests(long shopId) {
        return jdbc.query(withdrawalSelect() + " WHERE r.shop_id = ? ORDER BY r.requested_at DESC, r.id DESC LIMIT 200",
                (rs, row) -> shopWithdrawalRow(rs), shopId);
    }

    public List<AdminWithdrawalRequestView> adminWithdrawalRequests(String status) {
        if (status != null && !List.of("PENDING", "APPROVED", "REJECTED", "PAID", "CANCELLED").contains(status)) {
            throw ApiException.badRequest("Trạng thái yêu cầu rút không hợp lệ.");
        }
        String sql = withdrawalSelect() + (status == null ? "" : " WHERE r.status = ?")
                + " ORDER BY CASE r.status WHEN 'PENDING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END, r.requested_at DESC, r.id DESC LIMIT 300";
        return status == null
                ? jdbc.query(sql, (rs, row) -> adminWithdrawalRow(rs))
                : jdbc.query(sql, (rs, row) -> adminWithdrawalRow(rs), status);
    }

    @Transactional
    public ShopWithdrawalRequestView cancelWithdrawalRequest(long shopId, long requestId) {
        WithdrawalState request = lockWithdrawalRequest(requestId, shopId);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể hủy yêu cầu đang chờ Admin duyệt.");
        }
        jdbc.update("UPDATE shop_withdrawal_requests SET status = 'CANCELLED', updated_at = NOW() WHERE id = ?", requestId);
        return shopWithdrawalRequest(requestId, shopId);
    }

    @Transactional
    public AdminWithdrawalRequestView approveWithdrawalRequest(long requestId, long adminUserId) {
        WithdrawalState request = lockWithdrawalRequest(requestId, null);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Yêu cầu này không còn ở trạng thái chờ duyệt.");
        }
        jdbc.update("""
                UPDATE shop_withdrawal_requests
                SET status = 'APPROVED', reviewed_by = ?, reviewed_at = NOW(), updated_at = NOW(), review_note = NULL
                WHERE id = ?
                """, adminUserId, requestId);
        return adminWithdrawalRequest(requestId);
    }

    @Transactional
    public AdminWithdrawalRequestView rejectWithdrawalRequest(long requestId, long adminUserId, String reason) {
        WithdrawalState request = lockWithdrawalRequest(requestId, null);
        if (!"PENDING".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể từ chối yêu cầu đang chờ Admin duyệt.");
        }
        jdbc.update("""
                UPDATE shop_withdrawal_requests
                SET status = 'REJECTED', reviewed_by = ?, reviewed_at = NOW(), review_note = ?, updated_at = NOW()
                WHERE id = ?
                """, adminUserId, requiredText(reason), requestId);
        return adminWithdrawalRequest(requestId);
    }

    @Transactional
    public AdminWithdrawalRequestView markWithdrawalPaid(long requestId, long adminUserId, String reference, String note) {
        WithdrawalState request = lockWithdrawalRequest(requestId, null);
        if (!"APPROVED".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể ghi nhận thanh toán sau khi đã duyệt yêu cầu.");
        }
        lockShop(request.shopId());
        jdbc.update("""
                INSERT INTO shop_wallet_transactions
                    (shop_id, withdrawal_request_id, entry_type, amount, external_reference, description, created_by)
                VALUES (?, ?, 'PAYOUT', ?, ?, 'Yêu cầu rút ví đã được chuyển khoản', ?)
                """, request.shopId(), requestId, -request.amount(), requiredText(reference), adminUserId);
        jdbc.update("""
                UPDATE shop_withdrawal_requests
                SET status = 'PAID', paid_at = NOW(), transfer_reference = ?, transfer_note = ?, updated_at = NOW()
                WHERE id = ?
                """, requiredText(reference), blankToNull(note), requestId);
        return adminWithdrawalRequest(requestId);
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
                       COALESCE(-SUM(w.amount) FILTER (WHERE w.entry_type = 'PAYOUT'), 0) AS payouts,
                       COALESCE((SELECT SUM(r.amount) FROM shop_withdrawal_requests r
                                 WHERE r.shop_id = s.id AND r.status IN ('PENDING', 'APPROVED')), 0) AS reserved_amount,
                       COALESCE(SUM(w.amount), 0) - COALESCE((SELECT SUM(r.amount) FROM shop_withdrawal_requests r
                                 WHERE r.shop_id = s.id AND r.status IN ('PENDING', 'APPROVED')), 0) AS available_balance
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

    private ShopWithdrawalRequestView shopWithdrawalRequest(long requestId, long shopId) {
        List<ShopWithdrawalRequestView> rows = jdbc.query(withdrawalSelect() + " WHERE r.id = ? AND r.shop_id = ?",
                (rs, row) -> shopWithdrawalRow(rs), requestId, shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy yêu cầu rút tiền.");
        return rows.get(0);
    }

    private AdminWithdrawalRequestView adminWithdrawalRequest(long requestId) {
        List<AdminWithdrawalRequestView> rows = jdbc.query(withdrawalSelect() + " WHERE r.id = ?",
                (rs, row) -> adminWithdrawalRow(rs), requestId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy yêu cầu rút tiền.");
        return rows.get(0);
    }

    private WithdrawalState lockWithdrawalRequest(long requestId, Long shopId) {
        List<WithdrawalState> rows = shopId == null
                ? jdbc.query("SELECT shop_id, amount, status FROM shop_withdrawal_requests WHERE id = ? FOR UPDATE",
                (rs, row) -> new WithdrawalState(rs.getLong("shop_id"), rs.getLong("amount"), rs.getString("status")), requestId)
                : jdbc.query("SELECT shop_id, amount, status FROM shop_withdrawal_requests WHERE id = ? AND shop_id = ? FOR UPDATE",
                (rs, row) -> new WithdrawalState(rs.getLong("shop_id"), rs.getLong("amount"), rs.getString("status")), requestId, shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy yêu cầu rút tiền.");
        return rows.get(0);
    }

    private void lockShop(long shopId) {
        List<Long> rows = jdbc.query("SELECT id FROM shops WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
    }

    private static String withdrawalSelect() {
        return """
                SELECT r.id, r.shop_id, s.name AS shop_name, r.amount, r.status, r.bank_code, r.bank_name,
                       r.account_name_encrypted, r.account_number_encrypted, r.account_number_last4, r.requested_at, r.reviewed_at, r.review_note,
                       r.paid_at, r.transfer_reference, r.transfer_note
                FROM shop_withdrawal_requests r JOIN shops s ON s.id = r.shop_id
                """;
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
                       COALESCE(-SUM(w.amount) FILTER (WHERE w.entry_type = 'PAYOUT'), 0) AS payouts,
                       COALESCE((SELECT SUM(r.amount) FROM shop_withdrawal_requests r
                                 WHERE r.shop_id = s.id AND r.status IN ('PENDING', 'APPROVED')), 0) AS reserved_amount,
                       COALESCE(SUM(w.amount), 0) - COALESCE((SELECT SUM(r.amount) FROM shop_withdrawal_requests r
                                 WHERE r.shop_id = s.id AND r.status IN ('PENDING', 'APPROVED')), 0) AS available_balance
                FROM shops s LEFT JOIN shop_wallet_transactions w ON w.shop_id = s.id
                WHERE s.id = ? GROUP BY s.id
                """, WalletService::summaryRow, shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        WalletSummary summary = rows.get(0);
        return new WalletView(summary.shopId(), summary.shopName(), summary.commissionTier(), summary.commissionRate(),
                summary.approvedAt(), summary.balance(), summary.reservedAmount(), summary.availableBalance(),
                summary.grossSales(), summary.commissions(), summary.payouts());
    }

    private long balance(long shopId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM shop_wallet_transactions WHERE shop_id = ?", Long.class, shopId);
        return value == null ? 0 : value;
    }

    private long reservedAmount(long shopId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM shop_withdrawal_requests
                WHERE shop_id = ? AND status IN ('PENDING', 'APPROVED')
                """, Long.class, shopId);
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
                rs.getLong("balance"), rs.getLong("reserved_amount"), rs.getLong("available_balance"),
                rs.getLong("gross_sales"), rs.getLong("commissions"), rs.getLong("payouts"));
    }

    private ShopWithdrawalRequestView shopWithdrawalRow(ResultSet rs) throws SQLException {
        return new ShopWithdrawalRequestView(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                rs.getLong("amount"), rs.getString("status"), rs.getString("bank_code"), rs.getString("bank_name"),
                decryptAccountNumber(rs.getString("account_name_encrypted")), "•••• " + rs.getString("account_number_last4"), rs.getTimestamp("requested_at").toInstant(),
                instantOrNull(rs, "reviewed_at"), rs.getString("review_note"), instantOrNull(rs, "paid_at"),
                rs.getString("transfer_reference"), rs.getString("transfer_note"));
    }

    private AdminWithdrawalRequestView adminWithdrawalRow(ResultSet rs) throws SQLException {
        String accountNumber = decryptAccountNumber(rs.getString("account_number_encrypted"));
        if (!List.of("PENDING", "APPROVED").contains(rs.getString("status"))) {
            accountNumber = "•••• " + rs.getString("account_number_last4");
        }
        return new AdminWithdrawalRequestView(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                rs.getLong("amount"), rs.getString("status"), rs.getString("bank_code"), rs.getString("bank_name"),
                decryptAccountNumber(rs.getString("account_name_encrypted")), accountNumber, rs.getTimestamp("requested_at").toInstant(),
                instantOrNull(rs, "reviewed_at"), rs.getString("review_note"), instantOrNull(rs, "paid_at"),
                rs.getString("transfer_reference"), rs.getString("transfer_note"));
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String encryptAccountNumber(String value) {
        try {
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw ApiException.unavailable("Không mã hóa được số tài khoản nhận tiền.");
        }
    }

    private String decryptAccountNumber(String value) {
        try {
            if (value == null || !value.startsWith("v1:")) throw new GeneralSecurityException("Unsupported encrypted value");
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApiException.unavailable("Không giải mã được thông tin tài khoản. Kiểm tra WALLET_ACCOUNT_ENCRYPTION_KEY.");
        }
    }

    private SecretKeySpec encryptionKey() {
        try {
            byte[] key = Base64.getDecoder().decode(accountEncryptionKey == null ? "" : accountEncryptionKey.trim());
            if (key.length != 32) throw new IllegalArgumentException("Expected 32 bytes");
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw ApiException.unavailable("Cần cấu hình WALLET_ACCOUNT_ENCRYPTION_KEY bằng khóa Base64 32 byte để xử lý yêu cầu rút.");
        }
    }

    private static String lastFour(String value) {
        return value.substring(Math.max(0, value.length() - 4));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requiredText(String value) {
        return value == null ? "" : value.trim();
    }

    private record WalletOrder(long subtotal) {}
    private record OrderForCod(long shopId, String paymentStatus) {}
    private record CommissionSetting(String tier, BigDecimal customRate, Instant approvedAt) {}
    private record WithdrawalState(long shopId, long amount, String status) {}
    public record WalletSummary(long shopId, String shopName, String commissionTier, BigDecimal commissionRate,
                                Instant approvedAt, long balance, long reservedAmount, long availableBalance,
                                long grossSales, long commissions, long payouts) {}
    public record WalletView(long shopId, String shopName, String commissionTier, BigDecimal commissionRate,
                            Instant approvedAt, long balance, long reservedAmount, long availableBalance,
                            long grossSales, long commissions, long payouts) {}
    public record WalletTransaction(long id, Long orderId, String entryType, long amount, Long grossAmount,
                                    BigDecimal commissionRate, Long commissionAmount, String externalReference,
                                    String description, Instant createdAt) {}
    public record ShopWithdrawalRequestView(long id, long shopId, String shopName, long amount, String status,
                                            String bankCode, String bankName, String accountName,
                                            String maskedAccountNumber, Instant requestedAt, Instant reviewedAt,
                                            String reviewNote, Instant paidAt, String transferReference, String transferNote) {}
    public record AdminWithdrawalRequestView(long id, long shopId, String shopName, long amount, String status,
                                             String bankCode, String bankName, String accountName, String accountNumber,
                                             Instant requestedAt, Instant reviewedAt, String reviewNote,
                                             Instant paidAt, String transferReference, String transferNote) {}
}
