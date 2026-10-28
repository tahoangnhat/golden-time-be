package com.goldentime.api.commerce.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PayOSWebhookRegistrar implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PayOSWebhookRegistrar.class);
    private final PayOSClient payOS;
    private final String webhookUrl;

    public PayOSWebhookRegistrar(PayOSClient payOS,
                                 @Value("${app.payment.payos.webhook-url:https://golden-time-be.onrender.com/api/payments/payos/webhook}") String webhookUrl) {
        this.payOS = payOS;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!payOS.isConfigured()) return;
        try {
            payOS.confirmWebhook(webhookUrl);
            log.info("PayOS webhook registered.");
        } catch (Exception exception) {
            log.warn("Could not confirm PayOS webhook URL. Check the PayOS channel and public backend URL.");
        }
    }
}
