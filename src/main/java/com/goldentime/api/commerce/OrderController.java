package com.goldentime.api.commerce;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.commerce.payment.PayOSPaymentService;
import com.goldentime.api.commerce.payment.WalletService;
import com.goldentime.api.commerce.shipping.GhnShippingService;
import com.goldentime.api.commerce.voucher.VoucherRecords;
import com.goldentime.api.commerce.voucher.VoucherService;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/orders")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class OrderController {
    private final JdbcTemplate jdbc;
    private final PayOSPaymentService payments;
    private final WalletService wallet;
    private final GhnShippingService shipping;
    private final VoucherService voucherService;
    private final String frontendUrl;
    private final int paymentLinkExpiryMinutes;

    public OrderController(JdbcTemplate jdbc, PayOSPaymentService payments, WalletService wallet,
                           GhnShippingService shipping, VoucherService voucherService,
                           @Value("${app.payment.frontend-url:http://localhost:8080}") String frontendUrl,
                           @Value("${app.payment.payos.link-expiry-minutes:15}") int paymentLinkExpiryMinutes) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.wallet = wallet;
        this.shipping = shipping;
        this.voucherService = voucherService;
        this.frontendUrl = frontendUrl;
        this.paymentLinkExpiryMinutes = paymentLinkExpiryMinutes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderDetail create(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        String payment = request.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!List.of("COD", "PAYOS").contains(payment)) throw ApiException.badRequest("Phương thức thanh toán không hợp lệ.");
        String paymentStatus = "PAYOS".equals(payment) ? "PENDING" : "UNPAID";

        List<ItemRequest> items = request.items().stream().sorted(Comparator.comparing(ItemRequest::productId)).toList();
        List<LockedProduct> products = new ArrayList<>();
        long shopId = -1;
        long subtotal = 0;
        for (ItemRequest item : items) {
            LockedProduct product = lockProduct(item.productId());
            if (!"ON_SALE".equals(product.status()) || product.stockKg().compareTo(item.quantityKg()) < 0) {
                throw ApiException.badRequest("Sản phẩm " + product.name() + " không đủ tồn kho.");
            }
            if (shopId == -1) shopId = product.shopId();
            else if (shopId != product.shopId()) throw ApiException.badRequest("Mỗi đơn chỉ được đặt sản phẩm từ một cửa hàng.");
            long lineTotal = BigDecimal.valueOf(product.pricePerKg()).multiply(item.quantityKg())
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            subtotal = Math.addExact(subtotal, lineTotal);
            products.add(product.withRequest(item.quantityKg(), lineTotal));
        }
        if (subtotal <= 0) throw ApiException.badRequest("Giỏ hàng không có giá trị hợp lệ.");
        String province = request.deliveryProvinceName().trim();
        String ward = request.deliveryWardName().trim();
        String fullAddress = String.join(", ", request.deliveryAddress().trim(), ward, province);
        List<GhnShippingService.ShippingItem> shippingItems = products.stream()
                .map(product -> new GhnShippingService.ShippingItem(product.name(), product.quantityKg(), product.lineTotal())).toList();
        GhnShippingService.Quote quote = shipping.quote(shopId, request.deliveryName().trim(), request.deliveryPhone().trim(),
                fullAddress, province, ward, shippingItems, subtotal, "COD".equals(payment) ? subtotal : 0);
        long shippingFee = quote.shippingFee();

        long discount = 0;
        Long voucherId = null;
        Long buyerVoucherId = request.buyerVoucherId();
        if (buyerVoucherId != null) {
            VoucherRecords.ValidatedVoucher validated = voucherService.validateForCheckout(buyerVoucherId, userId, shopId, subtotal);
            discount = validated.discountAmount();
            voucherId = validated.voucherId();
        }
        long netSubtotal = Math.max(0, subtotal - discount);
        long total = Math.addExact(netSubtotal, shippingFee);

        Long orderId = jdbc.queryForObject("""
                INSERT INTO orders (user_id, shop_id, status, total_amount, note, payment_method, payment_status,
                    delivery_name, delivery_phone, delivery_address, delivery_province_name, delivery_ward_name,
                    subtotal_amount, shipping_fee, discount_amount, voucher_id, buyer_voucher_id)
                VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, shopId, total, blankToNull(request.note()), payment, paymentStatus,
                request.deliveryName().trim(), request.deliveryPhone().trim(), fullAddress, province, ward, subtotal, shippingFee,
                discount, voucherId, buyerVoucherId);

        if (buyerVoucherId != null) {
            voucherService.reserveVoucher(buyerVoucherId, orderId);
        }
        for (LockedProduct product : products) {
            int updated = jdbc.update("UPDATE shop_products SET stock_kg = stock_kg - ? WHERE id = ? AND stock_kg >= ?",
                    product.quantityKg(), product.id(), product.quantityKg());
            if (updated != 1) throw ApiException.badRequest("Tồn kho vừa thay đổi. Vui lòng tải lại giỏ hàng.");
            jdbc.update("""
                    INSERT INTO order_items (order_id, shop_product_id, quantity_kg, unit_price, line_total)
                    VALUES (?, ?, ?, ?, ?)
                    """, orderId, product.id(), product.quantityKg(), product.pricePerKg(), product.lineTotal());
        }
        if ("PAYOS".equals(payment)) {
            Instant expiresAt = Instant.now().plus(Duration.ofMinutes(paymentLinkExpiryMinutes));
            String baseUrl = frontendUrl.replaceAll("/+$", "");
            String returnUrl = baseUrl + "/shop";
            String cancelUrl = baseUrl + "/cart";
            String encodedOrder = Long.toString(orderId, 36).toUpperCase(Locale.ROOT);
            String description = "G" + encodedOrder.substring(Math.max(0, encodedOrder.length() - 8));
            var link = payments.createPaymentLink(orderId, total, description,
                    cancelUrl, returnUrl, expiresAt.getEpochSecond());
            jdbc.update("""
                    UPDATE orders SET payos_payment_link_id = ?, payos_checkout_url = ?, payos_qr_code = ?,
                        payment_expires_at = ? WHERE id = ?
                    """, link.paymentLinkId(), link.checkoutUrl(), link.qrCode(), Timestamp.from(expiresAt), orderId);
        }
        return detail(orderId, userId);
    }

    @GetMapping("/mine")
    public List<OrderSummary> mine(Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return jdbc.query("""
                SELECT o.id, o.shop_id, s.name AS shop_name, o.status, o.total_amount,
                       o.payment_method, o.payment_status, o.created_at, o.shop_rating, COUNT(oi.id) AS item_count,
                       sh.carrier_order_code, sh.carrier_status, sh.expected_delivery_at
                FROM orders o JOIN shops s ON s.id = o.shop_id
                LEFT JOIN order_items oi ON oi.order_id = o.id
                LEFT JOIN order_shipments sh ON sh.order_id = o.id
                WHERE o.user_id = ?
                GROUP BY o.id, s.name, sh.carrier_order_code, sh.carrier_status, sh.expected_delivery_at
                ORDER BY o.created_at DESC
                """, (rs, row) -> new OrderSummary(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("shop_name"), rs.getString("status"), rs.getLong("total_amount"),
                rs.getString("payment_method"), rs.getString("payment_status"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("item_count"), rs.getString("carrier_order_code"),
                rs.getString("carrier_status"), rs.getTimestamp("expected_delivery_at") == null ? null
                        : rs.getTimestamp("expected_delivery_at").toInstant(), (Integer) rs.getObject("shop_rating")), userId);
    }

    @GetMapping("/{id}")
    public OrderDetail get(@PathVariable long id, Authentication authentication) {
        return detail(id, CurrentUser.id(authentication));
    }

    @PostMapping("/{id}/rating")
    @Transactional
    public OrderRatingResponse rateOrder(@PathVariable long id, @Valid @RequestBody RateOrderRequest request,
                                         Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        List<RatingOrder> matchingOrders = jdbc.query("""
                SELECT shop_id, status, shop_rating
                FROM orders WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new RatingOrder(rs.getLong("shop_id"), rs.getString("status"),
                (Integer) rs.getObject("shop_rating")), id, userId);
        if (matchingOrders.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn hàng.");

        RatingOrder order = matchingOrders.get(0);
        if (!"DELIVERED".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể chấm điểm sau khi xác nhận đã nhận hàng.");
        }
        if (order.rating() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Đơn hàng này đã được chấm điểm.");
        }

        int updated = jdbc.update("""
                UPDATE orders SET shop_rating = ?
                WHERE id = ? AND user_id = ? AND status = 'DELIVERED' AND shop_rating IS NULL
                """, request.rating(), id, userId);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "Trạng thái đơn hàng vừa thay đổi. Vui lòng tải lại.");

        jdbc.update("""
                UPDATE shops SET
                    rating_avg = (SELECT COALESCE(ROUND(AVG(shop_rating)::numeric, 2), 0)
                                  FROM orders WHERE shop_id = ? AND shop_rating IS NOT NULL),
                    review_count = (SELECT COUNT(*) FROM orders WHERE shop_id = ? AND shop_rating IS NOT NULL)
                WHERE id = ?
                """, order.shopId(), order.shopId(), order.shopId());
        return new OrderRatingResponse(id, request.rating());
    }

    @PostMapping("/{id}/sync-payment")
    public OrderDetail syncPayment(@PathVariable long id, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        payments.syncPendingOrder(id, userId);
        return detail(id, userId);
    }

    @PostMapping("/{id}/confirm-received")
    @Transactional
    public ReceiptConfirmation confirmReceived(@PathVariable long id, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        List<ReceiptOrder> orders = jdbc.query("""
                SELECT shop_id, status, payos_reference
                FROM orders WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new ReceiptOrder(rs.getLong("shop_id"), rs.getString("status"),
                rs.getString("payos_reference")), id, userId);
        if (orders.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn hàng.");
        ReceiptOrder order = orders.get(0);
        if (!"IN_TRANSIT".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Chỉ có thể xác nhận nhận hàng khi đơn đang giao.");
        }
        int updated = jdbc.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ? AND user_id = ? AND status = 'IN_TRANSIT'",
                id, userId);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "Trạng thái đơn hàng vừa thay đổi.");
        voucherService.markVoucherUsed(id);
        boolean walletCredited = wallet.recordPaidOrder(id, order.shopId(), order.paymentReference(), null);
        return new ReceiptConfirmation(walletCredited);
    }

    private OrderDetail detail(long orderId, long userId) {
        List<OrderHeader> headers = jdbc.query("""
                SELECT o.id, o.shop_id, s.name AS shop_name, o.status, o.total_amount,
                       o.subtotal_amount, o.shipping_fee, COALESCE(o.discount_amount, 0) AS discount_amount,
                       o.voucher_id, o.buyer_voucher_id,
                       o.payment_method, o.payment_status,
                       o.payos_checkout_url, o.payos_qr_code, o.payment_expires_at, o.note,
                       o.delivery_name, o.delivery_phone, o.delivery_address, o.created_at,
                       sh.carrier_order_code, sh.carrier_status, sh.carrier_fee, sh.expected_delivery_at
                FROM orders o JOIN shops s ON s.id = o.shop_id
                LEFT JOIN order_shipments sh ON sh.order_id = o.id
                WHERE o.id = ? AND o.user_id = ?
                """, (rs, row) -> new OrderHeader(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                rs.getString("status"), rs.getLong("total_amount"), rs.getLong("subtotal_amount"),
                rs.getLong("shipping_fee"), rs.getLong("discount_amount"), (Long) rs.getObject("voucher_id"),
                (Long) rs.getObject("buyer_voucher_id"),
                rs.getString("payment_method"), rs.getString("payment_status"),
                rs.getString("payos_checkout_url"), rs.getString("payos_qr_code"),
                rs.getTimestamp("payment_expires_at") == null ? null : rs.getTimestamp("payment_expires_at").toInstant(),
                rs.getString("note"), rs.getString("delivery_name"), rs.getString("delivery_phone"),
                rs.getString("delivery_address"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("carrier_order_code"), rs.getString("carrier_status"), rs.getLong("carrier_fee"),
                rs.getTimestamp("expected_delivery_at") == null ? null : rs.getTimestamp("expected_delivery_at").toInstant()), orderId, userId);
        if (headers.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn hàng.");
        OrderHeader header = headers.get(0);
        List<OrderItem> items = jdbc.query("""
                SELECT oi.shop_product_id, p.display_name, COALESCE(f.emoji, '🍎') AS emoji, oi.quantity_kg,
                       oi.unit_price, oi.line_total
                FROM order_items oi
                JOIN shop_products p ON p.id = oi.shop_product_id
                LEFT JOIN fruits f ON f.id = p.fruit_id
                WHERE oi.order_id = ? ORDER BY oi.id
                """, (rs, row) -> new OrderItem(rs.getLong("shop_product_id"), rs.getString("display_name"),
                rs.getString("emoji"), rs.getBigDecimal("quantity_kg"), rs.getLong("unit_price"), rs.getLong("line_total")), orderId);
        return new OrderDetail(header.id(), header.shopId(), header.shopName(), header.status(), header.subtotal(),
                header.shippingFee(), header.discountAmount(), header.voucherId(), header.buyerVoucherId(),
                header.total(), header.paymentMethod(), header.paymentStatus(),
                header.checkoutUrl(), header.qrCode(), header.paymentExpiresAt(), header.note(), header.deliveryName(),
                header.deliveryPhone(), header.deliveryAddress(), header.createdAt(), header.trackingCode(),
                header.carrierStatus(), header.carrierFee(), header.expectedDeliveryAt(), items);
    }

    @PostMapping("/{id}/cancel-payment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelPayment(@PathVariable long id, Authentication authentication) {
        payments.cancelPendingOrder(id, CurrentUser.id(authentication));
    }

    private LockedProduct lockProduct(long id) {
        List<LockedProduct> rows = jdbc.query("""
                SELECT p.id, p.shop_id, p.display_name, p.price_per_kg, p.stock_kg, p.status
                FROM shop_products p JOIN shops s ON s.id = p.shop_id
                WHERE p.id = ? AND s.status = 'ACTIVE'
                FOR UPDATE OF p
                """, (rs, row) -> new LockedProduct(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("display_name"), rs.getLong("price_per_kg"), rs.getBigDecimal("stock_kg"),
                rs.getString("status"), null, 0), id);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy sản phẩm đang bán.");
        return rows.get(0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record LockedProduct(long id, long shopId, String name, long pricePerKg,
                                 BigDecimal stockKg, String status, BigDecimal quantityKg, long lineTotal) {
        LockedProduct withRequest(BigDecimal quantity, long total) {
            return new LockedProduct(id, shopId, name, pricePerKg, stockKg, status, quantity, total);
        }
    }
    private record OrderHeader(long id, long shopId, String shopName, String status, long total,
                               long subtotal, long shippingFee, long discountAmount, Long voucherId, Long buyerVoucherId,
                               String paymentMethod, String paymentStatus,
                               String checkoutUrl, String qrCode, Instant paymentExpiresAt, String note,
                               String deliveryName, String deliveryPhone, String deliveryAddress, Instant createdAt,
                               String trackingCode, String carrierStatus, long carrierFee, Instant expectedDeliveryAt) {}
    private record ReceiptOrder(long shopId, String status, String paymentReference) {}

    public record ItemRequest(@NotNull Long productId,
                              @NotNull @DecimalMin(value = "0.01") @Digits(integer = 7, fraction = 2) BigDecimal quantityKg) {}
    public record CreateOrderRequest(@NotEmpty @Size(max = 30) List<@Valid ItemRequest> items,
                                     @NotBlank @Size(max = 32) String paymentMethod,
                                     @NotBlank @Size(max = 255) String deliveryName,
                                     @NotBlank @Size(max = 32) String deliveryPhone,
                                     @NotBlank @Size(max = 400) String deliveryAddress,
                                     @NotBlank @Size(max = 255) String deliveryProvinceName,
                                     @NotBlank @Size(max = 255) String deliveryWardName,
                                     @Size(max = 2000) String note,
                                     Long buyerVoucherId) {}
    public record OrderItem(long productId, String name, String emoji, BigDecimal quantityKg,
                            long unitPrice, long lineTotal) {}
    public record OrderDetail(long id, long shopId, String shopName, String status, long subtotal,
                              long shippingFee, long discountAmount, Long voucherId, Long buyerVoucherId,
                              long total, String paymentMethod, String paymentStatus,
                              String checkoutUrl, String qrCode, Instant paymentExpiresAt, String note,
                              String deliveryName, String deliveryPhone, String deliveryAddress,
                              Instant createdAt, String trackingCode, String carrierStatus, long carrierFee,
                              Instant expectedDeliveryAt, List<OrderItem> items) {}
    public record OrderSummary(long id, long shopId, String shopName, String status, long total,
                               String paymentMethod, String paymentStatus, Instant createdAt, int itemCount,
                               String trackingCode, String carrierStatus, Instant expectedDeliveryAt, Integer shopRating) {}
    public record RateOrderRequest(@Min(1) @Max(5) int rating) {}
    public record OrderRatingResponse(long orderId, int rating) {}
    private record RatingOrder(long shopId, String status, Integer rating) {}
    public record ReceiptConfirmation(boolean walletCredited) {}
}
