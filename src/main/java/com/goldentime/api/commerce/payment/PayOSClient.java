package com.goldentime.api.commerce.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Component
public class PayOSClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String clientId;
    private final String apiKey;
    private final String checksumKey;

    public PayOSClient(RestClient.Builder builder, ObjectMapper mapper,
                       @Value("${app.payment.payos.api-url:https://api-merchant.payos.vn}") String apiUrl,
                       @Value("${app.payment.payos.client-id:}") String clientId,
                       @Value("${app.payment.payos.api-key:}") String apiKey,
                       @Value("${app.payment.payos.checksum-key:}") String checksumKey) {
        this.client = builder.baseUrl(apiUrl).build();
        this.mapper = mapper;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.checksumKey = checksumKey;
    }

    public boolean isConfigured() {
        return hasValue(clientId) && hasValue(apiKey) && hasValue(checksumKey);
    }

    public PaymentLink createPaymentLink(long orderCode, long amount, String description,
                                         String cancelUrl, String returnUrl, long expiresAt) {
        requireConfigured();
        Map<String, Object> signedFields = new TreeMap<>();
        signedFields.put("amount", amount);
        signedFields.put("cancelUrl", cancelUrl);
        signedFields.put("description", description);
        signedFields.put("orderCode", orderCode);
        signedFields.put("returnUrl", returnUrl);

        Map<String, Object> request = new TreeMap<>(signedFields);
        request.put("expiredAt", expiresAt);
        request.put("signature", signPaymentRequest(signedFields));
        JsonNode data = post("/v2/payment-requests", request);
        if (data.path("orderCode").asLong(-1) != orderCode || data.path("amount").asLong(-1) != amount) {
            throw ApiException.unavailable("PayOS trả về thông tin thanh toán không khớp với đơn hàng.");
        }
        String checkoutUrl = data.path("checkoutUrl").asText("");
        String paymentLinkId = data.path("paymentLinkId").asText("");
        if (checkoutUrl.isBlank() || paymentLinkId.isBlank()) {
            throw ApiException.unavailable("PayOS chưa trả về link thanh toán hợp lệ.");
        }
        return new PaymentLink(paymentLinkId, checkoutUrl, data.path("qrCode").asText(null));
    }

    public PaymentLinkStatus getPaymentLinkStatus(long orderCode) {
        requireConfigured();
        JsonNode data = get("/v2/payment-requests/" + orderCode);
        return new PaymentLinkStatus(data.path("status").asText(""), data.path("amountPaid").asLong(0));
    }

    public PaymentLinkStatus cancelPaymentLink(long orderCode, String reason) {
        requireConfigured();
        JsonNode response = post("/v2/payment-requests/" + orderCode + "/cancel", Map.of("cancellationReason", reason));
        return new PaymentLinkStatus(response.path("status").asText(""), response.path("amountPaid").asLong(0));
    }

    public void confirmWebhook(String webhookUrl) {
        requireConfigured();
        post("/confirm-webhook", Map.of("webhookUrl", webhookUrl));
    }

    public boolean verifyWebhook(JsonNode payload) {
        if (!isConfigured() || payload == null || !payload.hasNonNull("data") || !payload.hasNonNull("signature")) return false;
        return verifyDataSignature(payload.path("data"), payload.path("signature").asText());
    }

    private JsonNode get(String path) {
        try {
            JsonNode response = client.get().uri(path)
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .retrieve().body(JsonNode.class);
            return responseData(response);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("Không thể kết nối PayOS. Vui lòng thử lại sau.");
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            JsonNode response = client.post().uri(path)
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            return responseData(response);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("Không thể kết nối PayOS. Vui lòng thử lại sau.");
        }
    }

    private JsonNode responseData(JsonNode response) {
        if (response == null || !"00".equals(response.path("code").asText()) || !response.hasNonNull("data")) {
            throw ApiException.unavailable("PayOS từ chối yêu cầu thanh toán. Vui lòng kiểm tra cấu hình kênh thanh toán.");
        }
        return response.path("data");
    }

    private String signPaymentRequest(Map<String, Object> fields) {
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right).orElse("");
        return hmac(canonical);
    }

    private boolean verifyDataSignature(JsonNode data, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank() || data == null || !data.isObject()) return false;
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        data.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + signatureValue(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        byte[] expected = hmac(canonical).getBytes(StandardCharsets.US_ASCII);
        byte[] received = receivedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }

    private String signatureValue(JsonNode value) {
        if (value == null || value.isNull() || "null".equalsIgnoreCase(value.asText())
                || "undefined".equalsIgnoreCase(value.asText())) return "";
        if (value.isTextual()) return value.asText();
        if (value.isValueNode()) return value.asText();
        try {
            return mapper.writeValueAsString(sortJson(value));
        } catch (Exception exception) {
            return value.toString();
        }
    }

    private JsonNode sortJson(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> sorted.set(key, sortJson(child)));
            return sorted;
        }
        if (value.isArray()) {
            var sorted = mapper.createArrayNode();
            value.forEach(child -> sorted.add(sortJson(child)));
            return sorted;
        }
        return value;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo chữ ký PayOS.", exception);
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private void requireConfigured() {
        if (!isConfigured()) throw ApiException.unavailable("Chưa cấu hình PayOS trên sàn.");
    }

    public record PaymentLink(String paymentLinkId, String checkoutUrl, String qrCode) {}
    public record PaymentLinkStatus(String status, long amountPaid) {}
}
