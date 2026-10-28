package com.goldentime.api.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldentime.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class PayOSPaymentService {
    private static final Logger log = LoggerFactory.getLogger(PayOSPaymentService.class);
    private final JdbcTemplate jdbc;
    private final PayOSClient payOS;
    private final TransactionTemplate transactions;

    public PayOSPaymentService(JdbcTemplate jdbc, PayOSClient payOS,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.payOS = payOS;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PayOSClient.PaymentLink createPaymentLink(long orderCode, long amount, String description,
                                                       String cancelUrl, String returnUrl, long expiresAt) {
        return payOS.createPaymentLink(orderCode, amount, description, cancelUrl, returnUrl, expiresAt);
    }

    public boolean receiveWebhook(JsonNode payload) {
        if (!payOS.verifyWebhook(payload)) return false;
        JsonNode data = payload.path("data");
        if (!payload.path("success").asBoolean(false)
                || !"00".equals(payload.path("code").asText())
                || !"00".equals(data.path("code").asText())) return true;

        long orderCode = data.path("orderCode").asLong(-1);
        long amount = data.path("amount").asLong(-1);
        if (orderCode <= 0 || amount < 0) return true;
        List<Long> shopIds = jdbc.query("SELECT shop_id FROM orders WHERE id = ? AND payment_method = 'PAYOS'", (rs, row) -> rs.getLong(1), orderCode);
        if (shopIds.isEmpty()) return true;
        long shopId = shopIds.get(0);
        if (!markPaid(orderCode, shopId, amount, data.path("reference").asText(null), data.path("paymentLinkId").asText(null))) {
            log.warn("Bỏ qua webhook PayOS không khớp mã đơn hoặc tổng tiền (orderCode={}).", orderCode);
        }
        return true;
    }

    public void cancelPendingOrder(long orderId, long userId) {
        PaymentOrder order = findOrder(orderId, userId);
        if (!"PAYOS".equals(order.paymentMethod()) || !"PENDING".equals(order.paymentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Đơn hàng không còn chờ thanh toán PayOS.");
        }

        PayOSClient.PaymentLinkStatus remote = payOS.getPaymentLinkStatus(orderId);
        if (isPaid(remote, order.totalAmount())) {
            markPaid(orderId, order.shopId(), remote.amountPaid(), null, null);
            throw new ApiException(HttpStatus.CONFLICT, "Đơn hàng đã được thanh toán.");
        }
        if (remote.amountPaid() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PayOS đang đối soát giao dịch. Vui lòng thử lại sau.");
        }
        if ("PENDING".equals(remote.status())) {
            remote = payOS.cancelPaymentLink(orderId, "Customer cancelled the order");
        }
        if (isPaid(remote, order.totalAmount())) {
            markPaid(orderId, order.shopId(), remote.amountPaid(), null, null);
            throw new ApiException(HttpStatus.CONFLICT, "Đơn hàng đã được thanh toán.");
        }
        if (remote.amountPaid() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PayOS đang đối soát giao dịch. Vui lòng thử lại sau.");
        }
        if (!isClosed(remote.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PayOS chưa xác nhận hủy link. Vui lòng thử lại sau.");
        }
        cancelAndRestore(orderId);
    }

    @Scheduled(fixedDelayString = "${app.payment.payos.expiry-scan-ms:60000}")
    public void cancelExpiredPendingOrders() {
        if (!payOS.isConfigured()) return;
        List<PaymentOrder> expired = jdbc.query("""
                SELECT id, shop_id, total_amount, payment_method, payment_status
                FROM orders
                WHERE payment_method = 'PAYOS' AND payment_status = 'PENDING'
                  AND status = 'PENDING' AND payment_expires_at <= NOW()
                ORDER BY payment_expires_at
                LIMIT 50
                """, (rs, row) -> new PaymentOrder(rs.getLong("id"), rs.getLong("shop_id"), rs.getLong("total_amount"),
                rs.getString("payment_method"), rs.getString("payment_status")), new Object[]{});

        for (PaymentOrder order : expired) {
            try {
                PayOSClient.PaymentLinkStatus remote = payOS.getPaymentLinkStatus(order.id());
                if (isPaid(remote, order.totalAmount())) {
                    markPaid(order.id(), order.shopId(), remote.amountPaid(), null, null);
                } else if (remote.amountPaid() > 0) {
                    log.warn("PayOS đã nhận một phần tiền cho đơn hàng {}; giữ nguyên đơn để đối soát.", order.id());
                } else {
                    if ("PENDING".equals(remote.status())) {
                        remote = payOS.cancelPaymentLink(order.id(), "Payment link expired");
                    }
                    if (isPaid(remote, order.totalAmount())) markPaid(order.id(), order.shopId(), remote.amountPaid(), null, null);
                    else if (remote.amountPaid() > 0) log.warn("PayOS đã nhận một phần tiền cho đơn hàng {}; giữ nguyên đơn để đối soát.", order.id());
                    else if (isClosed(remote.status())) cancelAndRestore(order.id());
                }
            } catch (Exception exception) {
                log.warn("Không thể đồng bộ trạng thái link PayOS cho đơn hàng {}. Sẽ thử lại ở lần quét sau.", order.id());
            }
        }
    }

    private PaymentOrder findOrder(long orderId, long userId) {
        List<PaymentOrder> rows = jdbc.query("""
                SELECT id, shop_id, total_amount, payment_method, payment_status
                FROM orders WHERE id = ? AND user_id = ?
                """, (rs, row) -> new PaymentOrder(rs.getLong("id"), rs.getLong("shop_id"), rs.getLong("total_amount"),
                rs.getString("payment_method"), rs.getString("payment_status")), orderId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn hàng.");
        return rows.get(0);
    }

    private boolean markPaid(long orderId, long shopId, long amount, String reference, String paymentLinkId) {
        Integer updated = transactions.execute(status -> {
            int count = jdbc.update("""
                    UPDATE orders
                    SET payment_status = 'PAID', paid_at = COALESCE(paid_at, NOW()),
                        payos_reference = COALESCE(?, payos_reference),
                        payos_payment_link_id = COALESCE(?, payos_payment_link_id)
                    WHERE id = ? AND shop_id = ? AND payment_method = 'PAYOS' AND payment_status = 'PENDING'
                      AND total_amount = ?
                    """, reference, paymentLinkId, orderId, shopId, amount);
            return count;
        });
        if (updated == null || updated == 0) {
            Integer matching = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM orders WHERE id = ? AND shop_id = ? AND payment_method = 'PAYOS'
                      AND payment_status = 'PAID' AND total_amount = ?
                    """, Integer.class, orderId, shopId, amount);
            return matching != null && matching > 0;
        }
        return true;
    }

    private void cancelAndRestore(long orderId) {
        transactions.execute(status -> {
            int cancelled = jdbc.update("""
                    UPDATE orders SET status = 'CANCELLED', payment_status = 'CANCELLED'
                    WHERE id = ? AND payment_method = 'PAYOS' AND payment_status = 'PENDING'
                      AND status = 'PENDING'
                    """, orderId);
            if (cancelled == 1) {
                jdbc.update("""
                        UPDATE shop_products p SET stock_kg = p.stock_kg + cancelled_items.quantity_kg
                        FROM (
                            SELECT shop_product_id, SUM(quantity_kg) AS quantity_kg
                            FROM order_items WHERE order_id = ? GROUP BY shop_product_id
                        ) cancelled_items
                        WHERE cancelled_items.shop_product_id = p.id
                        """, orderId);
            }
            return null;
        });
    }

    private static boolean isPaid(PayOSClient.PaymentLinkStatus status, long expectedAmount) {
        return "PAID".equals(status.status()) && status.amountPaid() == expectedAmount;
    }

    private static boolean isClosed(String status) {
        return "CANCELLED".equals(status) || "EXPIRED".equals(status);
    }

    private record PaymentOrder(long id, long shopId, long totalAmount, String paymentMethod, String paymentStatus) {}
}
