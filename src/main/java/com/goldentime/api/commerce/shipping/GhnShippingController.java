package com.goldentime.api.commerce.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldentime.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/shipping/ghn")
public class GhnShippingController {
    private final GhnShippingService shipping;
    private final JdbcTemplate jdbc;

    public GhnShippingController(GhnShippingService shipping, JdbcTemplate jdbc) {
        this.shipping = shipping;
        this.jdbc = jdbc;
    }

    @GetMapping("/provinces")
    public List<GhnShippingService.GhnAddress> provinces() {
        return shipping.provinces();
    }

    @GetMapping("/wards")
    public List<GhnShippingService.GhnAddress> wards(@RequestParam int provinceId) {
        return shipping.wards(provinceId);
    }

    @PostMapping("/quote")
    public ShippingQuote quote(@Valid @RequestBody QuoteRequest request) {
        List<QuoteProduct> products = new java.util.ArrayList<>();
        long shopId = -1;
        long subtotal = 0;
        for (QuoteItem item : request.items().stream().sorted(Comparator.comparing(QuoteItem::productId)).toList()) {
            List<QuoteProduct> rows = jdbc.query("""
                    SELECT p.id, p.shop_id, p.display_name, p.price_per_kg, p.stock_kg, p.status
                    FROM shop_products p JOIN shops s ON s.id = p.shop_id
                    WHERE p.id = ? AND s.status = 'ACTIVE'
                    """, (rs, row) -> new QuoteProduct(rs.getLong("id"), rs.getLong("shop_id"),
                    rs.getString("display_name"), rs.getLong("price_per_kg"), rs.getBigDecimal("stock_kg"),
                    rs.getString("status")), item.productId());
            if (rows.isEmpty()) throw ApiException.badRequest("Một sản phẩm trong giỏ hàng không còn bán.");
            QuoteProduct product = rows.get(0);
            if (!"ON_SALE".equals(product.status()) || product.stockKg().compareTo(item.quantityKg()) < 0) {
                throw ApiException.badRequest("Sản phẩm " + product.name() + " không đủ tồn kho.");
            }
            if (shopId < 0) shopId = product.shopId();
            else if (shopId != product.shopId()) throw ApiException.badRequest("Mỗi đơn chỉ được đặt sản phẩm từ một cửa hàng.");
            long lineTotal = BigDecimal.valueOf(product.pricePerKg()).multiply(item.quantityKg())
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            subtotal = Math.addExact(subtotal, lineTotal);
            products.add(product.withQuantity(item.quantityKg(), lineTotal));
        }
        if (shopId <= 0 || subtotal <= 0) throw ApiException.badRequest("Giỏ hàng không có giá trị hợp lệ.");
        String payment = request.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!List.of("COD", "PAYOS").contains(payment)) throw ApiException.badRequest("Phương thức thanh toán không hợp lệ.");
        String fullAddress = fullAddress(request.deliveryAddress(), request.deliveryWardName(), request.deliveryProvinceName());
        List<GhnShippingService.ShippingItem> shippingItems = products.stream()
                .map(item -> new GhnShippingService.ShippingItem(item.name(), item.quantityKg(), item.lineTotal())).toList();
        long codAmount = "COD".equals(payment) ? subtotal : 0;
        GhnShippingService.Quote quote = shipping.quote(shopId, request.deliveryName(), request.deliveryPhone(),
                fullAddress, request.deliveryProvinceName(), request.deliveryWardName(), shippingItems, subtotal, codAmount);
        return new ShippingQuote(quote.shippingFee(), quote.expectedDeliveryAt());
    }

    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void webhook(@RequestHeader(value = "X-GHN-Webhook-Secret", required = false) String secret,
                        @RequestBody JsonNode payload) {
        if (!shipping.webhookSecretMatches(secret)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Webhook GHN không hợp lệ.");
        }
        shipping.handleWebhook(payload);
    }

    private static String fullAddress(String street, String ward, String province) {
        return String.join(", ", street.trim(), ward.trim(), province.trim());
    }

    private record QuoteProduct(long id, long shopId, String name, long pricePerKg,
                                BigDecimal stockKg, String status, BigDecimal quantityKg, long lineTotal) {
        QuoteProduct(long id, long shopId, String name, long pricePerKg, BigDecimal stockKg, String status) {
            this(id, shopId, name, pricePerKg, stockKg, status, BigDecimal.ZERO, 0);
        }
        QuoteProduct withQuantity(BigDecimal quantity, long total) {
            return new QuoteProduct(id, shopId, name, pricePerKg, stockKg, status, quantity, total);
        }
    }

    public record QuoteItem(@NotNull @Positive Long productId,
                            @NotNull @DecimalMin("0.01") @Digits(integer = 7, fraction = 2) BigDecimal quantityKg) {}
    public record QuoteRequest(@NotEmpty @Size(max = 30) List<@Valid QuoteItem> items,
                               @NotBlank @Size(max = 255) String paymentMethod,
                               @NotBlank @Size(max = 255) String deliveryName,
                               @NotBlank @Size(max = 32) String deliveryPhone,
                               @NotBlank @Size(max = 400) String deliveryAddress,
                               @NotBlank @Size(max = 255) String deliveryProvinceName,
                               @NotBlank @Size(max = 255) String deliveryWardName) {}
    public record ShippingQuote(long shippingFee, Instant expectedDeliveryAt) {}
}
