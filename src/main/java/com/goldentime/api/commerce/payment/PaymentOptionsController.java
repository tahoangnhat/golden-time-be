package com.goldentime.api.commerce.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentOptionsController {
    private final PayOSClient payOS;

    public PaymentOptionsController(PayOSClient payOS) {
        this.payOS = payOS;
    }

    @GetMapping("/api/payment-methods")
    public PaymentMethods methods() {
        return new PaymentMethods(true, payOS.isConfigured());
    }

    public record PaymentMethods(boolean codEnabled, boolean payosEnabled) {}
}
