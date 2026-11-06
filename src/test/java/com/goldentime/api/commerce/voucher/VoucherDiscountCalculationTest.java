package com.goldentime.api.commerce.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoucherDiscountCalculationTest {

    @Test
    @DisplayName("PERCENT discount - tròn số")
    void testPercentDiscountExact() {
        // 200,000 * 10% = 20,000
        long discount = VoucherService.calculateDiscount(200_000L, "PERCENT", BigDecimal.valueOf(10), null);
        assertEquals(20_000L, discount);
    }

    @Test
    @DisplayName("PERCENT discount - làm tròn HALF_UP trực tiếp đến đơn vị tiền nguyên")
    void testPercentDiscountHalfUpRounding() {
        // 155,555 * 15% = 23,333.25 -> 23,333
        long discount = VoucherService.calculateDiscount(155_555L, "PERCENT", BigDecimal.valueOf(15), null);
        assertEquals(23_333L, discount);

        // 100,001 * 15.5% = 15,500.155 -> 15,500
        long discount2 = VoucherService.calculateDiscount(100_001L, "PERCENT", BigDecimal.valueOf(15.5), null);
        assertEquals(15_500L, discount2);

        // 100,005 * 15% = 15,000.75 -> 15,001 (làm tròn lên do >= 0.5)
        long discount3 = VoucherService.calculateDiscount(100_005L, "PERCENT", BigDecimal.valueOf(15), null);
        assertEquals(15_001L, discount3);
    }

    @Test
    @DisplayName("PERCENT discount - áp dụng trần max_discount_amount")
    void testPercentDiscountCappedByMaxAmount() {
        // 1,000,000 * 20% = 200,000 nhưng max là 50,000 -> 50,000
        long discount = VoucherService.calculateDiscount(1_000_000L, "PERCENT", BigDecimal.valueOf(20), 50_000L);
        assertEquals(50_000L, discount);

        // 200,000 * 20% = 40,000 chưa chạm trần 50,000 -> 40,000
        long discountUnderCap = VoucherService.calculateDiscount(200_000L, "PERCENT", BigDecimal.valueOf(20), 50_000L);
        assertEquals(40_000L, discountUnderCap);
    }

    @Test
    @DisplayName("FIXED discount - số tiền cố định")
    void testFixedDiscount() {
        long discount = VoucherService.calculateDiscount(500_000L, "FIXED", BigDecimal.valueOf(50_000), null);
        assertEquals(50_000L, discount);
    }

    @Test
    @DisplayName("FIXED discount - vượt quá subtotal thì giảm tối đa bằng subtotal (tiền hàng không âm)")
    void testDiscountCappedBySubtotal() {
        // Subtotal chỉ 30,000 nhưng voucher 50,000 -> giảm tối đa 30,000
        long discount = VoucherService.calculateDiscount(30_000L, "FIXED", BigDecimal.valueOf(50_000), null);
        assertEquals(30_000L, discount);
    }

    @Test
    @DisplayName("Subtotal bằng 0 hoặc âm -> discount bằng 0")
    void testZeroOrNegativeSubtotal() {
        long discount1 = VoucherService.calculateDiscount(0L, "PERCENT", BigDecimal.valueOf(10), null);
        assertEquals(0L, discount1);

        long discount2 = VoucherService.calculateDiscount(-100L, "FIXED", BigDecimal.valueOf(10_000), null);
        assertEquals(0L, discount2);
    }

    @Test
    @DisplayName("Kiểm tra công thức Commission & Wallet Credit sau khi áp dụng Voucher")
    void testWalletCommissionCalculation() {
        long subtotal = 500_000L;
        long discount = 100_000L;
        BigDecimal rate = BigDecimal.valueOf(5); // 5%

        long commissionBase = Math.max(0L, subtotal - discount); // 400,000
        assertEquals(400_000L, commissionBase);

        long commission = BigDecimal.valueOf(commissionBase).multiply(rate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        assertEquals(20_000L, commission);

        // Shop gross credit = commissionBase
        long shopGrossCredit = commissionBase;
        assertEquals(400_000L, shopGrossCredit);

        // Shop net revenue = credit - commission
        long shopNet = shopGrossCredit - commission;
        assertEquals(380_000L, shopNet);
    }

    @Test
    @DisplayName("Kiểm tra hồi quy đơn hàng không dùng Voucher (discount = 0)")
    void testNonVoucherRegression() {
        long subtotal = 500_000L;
        long discount = 0L;
        BigDecimal rate = BigDecimal.valueOf(5); // 5%

        long commissionBase = Math.max(0L, subtotal - discount);
        assertEquals(subtotal, commissionBase);

        long commission = BigDecimal.valueOf(commissionBase).multiply(rate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        assertEquals(25_000L, commission);
    }
}
