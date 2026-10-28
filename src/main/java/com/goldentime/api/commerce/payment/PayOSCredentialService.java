package com.goldentime.api.commerce.payment;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PayOSCredentialService {
    private final JdbcTemplate jdbc;
    private final PayOSCredentialCrypto crypto;
    private final PayOSClient payOS;
    private final String webhookBaseUrl;

    public PayOSCredentialService(JdbcTemplate jdbc, PayOSCredentialCrypto crypto, PayOSClient payOS,
                                  @Value("${app.payment.payos.webhook-base-url:https://golden-time-be.onrender.com/api/payments/payos/webhook}") String webhookBaseUrl) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.payOS = payOS;
        this.webhookBaseUrl = webhookBaseUrl.replaceAll("/+$", "");
    }

    public Settings settings(long shopId) {
        List<Boolean> connected = jdbc.query("""
                SELECT payos_client_id_encrypted IS NOT NULL
                   AND payos_api_key_encrypted IS NOT NULL
                   AND payos_checksum_key_encrypted IS NOT NULL
                   AND payos_webhook_verified_at IS NOT NULL
                FROM shops WHERE id = ?
                """, (rs, row) -> rs.getBoolean(1), shopId);
        if (connected.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        return new Settings(connected.get(0), webhookUrl(shopId));
    }

    public void connect(long shopId, String clientId, String apiKey, String checksumKey) {
        PayOSClient.Credentials credentials = new PayOSClient.Credentials(clientId.trim(), apiKey.trim(), checksumKey.trim());
        if (!PayOSClient.isConfigured(credentials)) throw ApiException.badRequest("Vui lòng nhập đủ ba khóa PayOS.");

        ensureNoPendingPayments(shopId);
        List<PreviousCredentials> previousRows = jdbc.query("""
                SELECT payos_client_id_encrypted, payos_api_key_encrypted, payos_checksum_key_encrypted,
                       payos_webhook_verified_at
                FROM shops WHERE id = ?
                """, (rs, row) -> new PreviousCredentials(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4)), shopId);
        if (previousRows.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        PreviousCredentials previous = previousRows.get(0);

        jdbc.update("""
                UPDATE shops
                SET payos_client_id_encrypted = ?, payos_api_key_encrypted = ?, payos_checksum_key_encrypted = ?,
                    payos_webhook_verified_at = NULL
                WHERE id = ?
                """, crypto.encrypt(credentials.clientId()), crypto.encrypt(credentials.apiKey()),
                crypto.encrypt(credentials.checksumKey()), shopId);

        try {
            payOS.confirmWebhook(credentials, webhookUrl(shopId));
            jdbc.update("UPDATE shops SET payos_webhook_verified_at = NOW() WHERE id = ?", shopId);
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE shops SET payos_client_id_encrypted = ?, payos_api_key_encrypted = ?,
                        payos_checksum_key_encrypted = ?, payos_webhook_verified_at = ?
                    WHERE id = ?
                    """, previous.clientId(), previous.apiKey(), previous.checksumKey(), previous.webhookVerifiedAt(), shopId);
            throw exception;
        }
    }

    @Transactional
    public void disconnect(long shopId) {
        ensureNoPendingPayments(shopId);
        jdbc.update("""
                UPDATE shops SET payos_client_id_encrypted = NULL, payos_api_key_encrypted = NULL,
                    payos_checksum_key_encrypted = NULL, payos_webhook_verified_at = NULL
                WHERE id = ?
                """, shopId);
    }

    private void ensureNoPendingPayments(long shopId) {
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders
                WHERE shop_id = ? AND payment_method = 'PAYOS'
                  AND payment_status = 'PENDING' AND status = 'PENDING'
                """, Integer.class, shopId);
        if (pending != null && pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Không thể thay đổi kết nối PayOS khi cửa hàng còn đơn chờ thanh toán.");
        }
    }

    public PayOSClient.Credentials requireConnected(long shopId) {
        PayOSClient.Credentials credentials = readCredentials(shopId, true);
        if (credentials == null) throw new ApiException(HttpStatus.CONFLICT, "Cửa hàng chưa kết nối PayOS.");
        return credentials;
    }

    public PayOSClient.Credentials forWebhook(long shopId) {
        return readCredentials(shopId, false);
    }

    public String webhookUrl(long shopId) {
        return webhookBaseUrl + "/" + shopId;
    }

    private PayOSClient.Credentials readCredentials(long shopId, boolean requireVerified) {
        List<EncryptedCredentials> rows = jdbc.query("""
                SELECT payos_client_id_encrypted, payos_api_key_encrypted, payos_checksum_key_encrypted,
                       payos_webhook_verified_at IS NOT NULL AS verified
                FROM shops WHERE id = ?
                """, (rs, row) -> new EncryptedCredentials(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), shopId);
        if (rows.isEmpty()) return null;
        EncryptedCredentials encrypted = rows.get(0);
        if (encrypted.clientId() == null || encrypted.apiKey() == null || encrypted.checksumKey() == null
                || (requireVerified && !encrypted.verified())) return null;
        return new PayOSClient.Credentials(crypto.decrypt(encrypted.clientId()), crypto.decrypt(encrypted.apiKey()),
                crypto.decrypt(encrypted.checksumKey()));
    }

    private record EncryptedCredentials(String clientId, String apiKey, String checksumKey, boolean verified) {}
    private record PreviousCredentials(String clientId, String apiKey, String checksumKey, java.sql.Timestamp webhookVerifiedAt) {}
    public record Settings(boolean connected, String webhookUrl) {}
}
