package com.goldentime.api.commerce.voucher;

public class TestRunner {
    public static void main(String[] args) {
        System.out.println("=== RUNNING VOUCHER & CHECKOUT DISCOUNT TESTS ===");
        VoucherDiscountCalculationTest test = new VoucherDiscountCalculationTest();
        
        test.testPercentDiscountExact();
        System.out.println("[PASS] testPercentDiscountExact");

        test.testPercentDiscountHalfUpRounding();
        System.out.println("[PASS] testPercentDiscountHalfUpRounding");

        test.testPercentDiscountCappedByMaxAmount();
        System.out.println("[PASS] testPercentDiscountCappedByMaxAmount");

        test.testFixedDiscount();
        System.out.println("[PASS] testFixedDiscount");

        test.testDiscountCappedBySubtotal();
        System.out.println("[PASS] testDiscountCappedBySubtotal");

        test.testZeroOrNegativeSubtotal();
        System.out.println("[PASS] testZeroOrNegativeSubtotal");

        test.testWalletCommissionCalculation();
        System.out.println("[PASS] testWalletCommissionCalculation");

        test.testNonVoucherRegression();
        System.out.println("[PASS] testNonVoucherRegression");

        System.out.println("=== ALL 8 VOUCHER & COMMISSION UNIT TESTS PASSED ===");
    }
}
