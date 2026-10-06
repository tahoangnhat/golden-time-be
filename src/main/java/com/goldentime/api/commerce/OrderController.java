package com.goldentime.api.commerce;

import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/orders")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class OrderController {
    private static final long SHIPPING_FEE = 15000;
    private final JdbcTemplate jdbc;

    public OrderController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderDetail create(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        String payment = request.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!List.of("COD", "WALLET", "CARD").contains(payment)) throw ApiException.badRequest("Phương thức thanh toán không hợp lệ.");

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
            else if (shopId != product.shopId()) throw ApiException.badRequest("Mỗi đơn demo chỉ được đặt sản phẩm từ một cửa hàng.");
            long lineTotal = BigDecimal.valueOf(product.pricePerKg()).multiply(item.quantityKg())
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            subtotal = Math.addExact(subtotal, lineTotal);
            products.add(product.withRequest(item.quantityKg(), lineTotal));
        }
        if (subtotal <= 0) throw ApiException.badRequest("Giỏ hàng không có giá trị hợp lệ.");
        long total = Math.addExact(subtotal, SHIPPING_FEE);

        Long orderId = jdbc.queryForObject("""
                INSERT INTO orders (user_id, shop_id, status, total_amount, note, payment_method,
                    delivery_name, delivery_phone, delivery_address, subtotal_amount, shipping_fee)
                VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, shopId, total, blankToNull(request.note()), payment,
                request.deliveryName().trim(), request.deliveryPhone().trim(), request.deliveryAddress().trim(), subtotal, SHIPPING_FEE);
        for (LockedProduct product : products) {
            int updated = jdbc.update("UPDATE shop_products SET stock_kg = stock_kg - ? WHERE id = ? AND stock_kg >= ?",
                    product.quantityKg(), product.id(), product.quantityKg());
            if (updated != 1) throw ApiException.badRequest("Tồn kho vừa thay đổi. Vui lòng tải lại giỏ hàng.");
            jdbc.update("""
                    INSERT INTO order_items (order_id, shop_product_id, quantity_kg, unit_price, line_total)
                    VALUES (?, ?, ?, ?, ?)
                    """, orderId, product.id(), product.quantityKg(), product.pricePerKg(), product.lineTotal());
        }
        return detail(orderId, userId);
    }

    @GetMapping("/mine")
    public List<OrderSummary> mine(Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return jdbc.query("""
                SELECT o.id, o.shop_id, s.name AS shop_name, o.status, o.total_amount,
                       o.payment_method, o.created_at, COUNT(oi.id) AS item_count
                FROM orders o JOIN shops s ON s.id = o.shop_id
                LEFT JOIN order_items oi ON oi.order_id = o.id
                WHERE o.user_id = ?
                GROUP BY o.id, s.name ORDER BY o.created_at DESC
                """, (rs, row) -> new OrderSummary(rs.getLong("id"), rs.getLong("shop_id"),
                rs.getString("shop_name"), rs.getString("status"), rs.getLong("total_amount"),
                rs.getString("payment_method"), rs.getTimestamp("created_at").toInstant(), rs.getInt("item_count")), userId);
    }

    @GetMapping("/{id}")
    public OrderDetail get(@PathVariable long id, Authentication authentication) {
        return detail(id, CurrentUser.id(authentication));
    }

    private OrderDetail detail(long orderId, long userId) {
        List<OrderHeader> headers = jdbc.query("""
                SELECT o.id, o.shop_id, s.name AS shop_name, o.status, o.total_amount,
                       o.subtotal_amount, o.shipping_fee, o.payment_method, o.note,
                       o.delivery_name, o.delivery_phone, o.delivery_address, o.created_at
                FROM orders o JOIN shops s ON s.id = o.shop_id
                WHERE o.id = ? AND o.user_id = ?
                """, (rs, row) -> new OrderHeader(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                rs.getString("status"), rs.getLong("total_amount"), rs.getLong("subtotal_amount"),
                rs.getLong("shipping_fee"), rs.getString("payment_method"), rs.getString("note"),
                rs.getString("delivery_name"), rs.getString("delivery_phone"), rs.getString("delivery_address"),
                rs.getTimestamp("created_at").toInstant()), orderId, userId);
        if (headers.isEmpty()) throw ApiException.notFound("Không tìm thấy đơn hàng.");
        OrderHeader header = headers.get(0);
        List<OrderItem> items = jdbc.query("""
                SELECT oi.shop_product_id, p.display_name, f.emoji, oi.quantity_kg,
                       oi.unit_price, oi.line_total
                FROM order_items oi
                JOIN shop_products p ON p.id = oi.shop_product_id
                JOIN fruits f ON f.id = p.fruit_id
                WHERE oi.order_id = ? ORDER BY oi.id
                """, (rs, row) -> new OrderItem(rs.getLong("shop_product_id"), rs.getString("display_name"),
                rs.getString("emoji"), rs.getBigDecimal("quantity_kg"), rs.getLong("unit_price"), rs.getLong("line_total")), orderId);
        return new OrderDetail(header.id(), header.shopId(), header.shopName(), header.status(), header.subtotal(),
                header.shippingFee(), header.total(), header.paymentMethod(), header.note(), header.deliveryName(),
                header.deliveryPhone(), header.deliveryAddress(), header.createdAt(), items);
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
                               long subtotal, long shippingFee, String paymentMethod, String note,
                               String deliveryName, String deliveryPhone, String deliveryAddress, Instant createdAt) {}

    public record ItemRequest(@NotNull Long productId,
                              @NotNull @DecimalMin(value = "0.01") @Digits(integer = 7, fraction = 2) BigDecimal quantityKg) {}
    public record CreateOrderRequest(@NotEmpty @Size(max = 30) List<@Valid ItemRequest> items,
                                     @NotBlank @Size(max = 32) String paymentMethod,
                                     @NotBlank @Size(max = 255) String deliveryName,
                                     @NotBlank @Size(max = 32) String deliveryPhone,
                                     @NotBlank @Size(max = 512) String deliveryAddress,
                                     @Size(max = 2000) String note) {}
    public record OrderItem(long productId, String name, String emoji, BigDecimal quantityKg,
                            long unitPrice, long lineTotal) {}
    public record OrderDetail(long id, long shopId, String shopName, String status, long subtotal,
                              long shippingFee, long total, String paymentMethod, String note,
                              String deliveryName, String deliveryPhone, String deliveryAddress,
                              Instant createdAt, List<OrderItem> items) {}
    public record OrderSummary(long id, long shopId, String shopName, String status, long total,
                               String paymentMethod, Instant createdAt, int itemCount) {}
}
