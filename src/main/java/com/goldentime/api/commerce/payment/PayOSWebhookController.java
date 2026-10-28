package com.goldentime.api.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/payments/payos")
public class PayOSWebhookController {
    private final PayOSPaymentService payments;

    public PayOSWebhookController(PayOSPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/webhook/{shopId}")
    public ResponseEntity<Map<String, String>> webhook(@PathVariable long shopId, @RequestBody JsonNode payload) {
        if (shopId <= 0 || !payments.receiveWebhook(shopId, payload)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Chữ ký webhook PayOS không hợp lệ."));
        }
        return ResponseEntity.ok(Map.of("received", "true"));
    }
}
