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

    public PayOSClient(RestClient.Builder builder, ObjectMapper mapper,
                       @Value("${app.payment.payos.api-url:https://api-merchant.payos.vn}") String apiUrl) {
        this.client = builder.baseUrl(apiUrl).build();
        this.mapper = mapper;
    }

    public PaymentLink createPaymentLink(Credentials credentials, long orderCode, long amount, String description,
                                         String cancelUrl, String returnUrl, long expiresAt) {
        requireConfigured(credentials);
        Map<String, Object> signedFields = new TreeMap<>();
        signedFields.put("amount", amount);
        signedFields.put("cancelUrl", cancelUrl);
        signedFields.put("description", description);
        signedFields.put("orderCode", orderCode);
        signedFields.put("returnUrl", returnUrl);

        Map<String, Object> request = new TreeMap<>(signedFields);
        request.put("expiredAt", expiresAt);
        request.put("signature", signPaymentRequest(credentials, signedFields));
        JsonNode data = post(credentials, "/v2/payment-requests", request);
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

    public PaymentLinkStatus getPaymentLinkStatus(Credentials credentials, long orderCode) {
        requireConfigured(credentials);
        JsonNode data = get(credentials, "/v2/payment-requests/" + orderCode);
        return new PaymentLinkStatus(data.path("status").asText(""), data.path("amountPaid").asLong(0));
    }

    public PaymentLinkStatus cancelPaymentLink(Credentials credentials, long orderCode, String reason) {
        requireConfigured(credentials);
        JsonNode response = post(credentials, "/v2/payment-requests/" + orderCode + "/cancel", Map.of("cancellationReason", reason));
        return new PaymentLinkStatus(response.path("status").asText(""), response.path("amountPaid").asLong(0));
    }

    public void confirmWebhook(Credentials credentials, String webhookUrl) {
        requireConfigured(credentials);
        JsonNode data = post(credentials, "/confirm-webhook", Map.of("webhookUrl", webhookUrl));
        if (!webhookUrl.equals(data.path("webhookUrl").asText())) {
            throw ApiException.unavailable("PayOS chưa xác nhận địa chỉ webhook cho cửa hàng.");
        }
    }

    public boolean verifyWebhook(Credentials credentials, JsonNode payload) {
        if (!isConfigured(credentials) || payload == null || !payload.hasNonNull("data") || !payload.hasNonNull("signature")) return false;
        return verifyDataSignature(credentials, payload.path("data"), payload.path("signature").asText());
    }

    private JsonNode get(Credentials credentials, String path) {
        try {
            JsonNode response = client.get().uri(path)
                    .header("x-client-id", credentials.clientId())
                    .header("x-api-key", credentials.apiKey())
                    .retrieve().body(JsonNode.class);
            return responseData(response);
        } catch (RestClientException exception) {
            throw ApiException.unavailable("Không thể kết nối PayOS. Vui lòng thử lại sau.");
        }
    }

    private JsonNode post(Credentials credentials, String path, Map<String, Object> body) {
        try {
            JsonNode response = client.post().uri(path)
                    .header("x-client-id", credentials.clientId())
                    .header("x-api-key", credentials.apiKey())
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

    private String signPaymentRequest(Credentials credentials, Map<String, Object> fields) {
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right).orElse("");
        return hmac(credentials, canonical);
    }

    private boolean verifyDataSignature(Credentials credentials, JsonNode data, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank() || data == null || !data.isObject()) return false;
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        data.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + signatureValue(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        byte[] expected = hmac(credentials, canonical).getBytes(StandardCharsets.US_ASCII);
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

    private String hmac(Credentials credentials, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credentials.checksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo chữ ký PayOS.", exception);
        }
    }

    public static boolean isConfigured(Credentials credentials) {
        return credentials != null && hasValue(credentials.clientId()) && hasValue(credentials.apiKey()) && hasValue(credentials.checksumKey());
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private void requireConfigured(Credentials credentials) {
        if (!isConfigured(credentials)) throw ApiException.unavailable("Cửa hàng chưa kết nối kênh PayOS.");
    }

    public record Credentials(String clientId, String apiKey, String checksumKey) {}
    public record PaymentLink(String paymentLinkId, String checkoutUrl, String qrCode) {}
    public record PaymentLinkStatus(String status, long amountPaid) {}
}
