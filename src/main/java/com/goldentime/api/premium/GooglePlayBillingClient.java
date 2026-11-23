package com.goldentime.api.premium;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

@Component
public class GooglePlayBillingClient {
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private static final String API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";

    private final ObjectMapper mapper;
    private final RestClient http;
    private final String packageName;
    private final String monthlyProductId;
    private final String annualProductId;
    private final String serviceAccountJson;
    private final boolean configured;
    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiry = Instant.EPOCH;

    public GooglePlayBillingClient(ObjectMapper mapper, RestClient.Builder builder,
                                   @Value("${app.premium.google-play.package-name:vn.goldentime.customer}") String packageName,
                                   @Value("${app.premium.google-play.monthly-product-id:golden_time_premium_30d}") String monthlyProductId,
                                   @Value("${app.premium.google-play.annual-product-id:golden_time_premium_365d}") String annualProductId,
                                   @Value("${app.premium.google-play.service-account-json:}") String serviceAccountJson) {
        this.mapper = mapper;
        this.http = builder.build();
        this.packageName = packageName;
        this.monthlyProductId = monthlyProductId;
        this.annualProductId = annualProductId;
        this.serviceAccountJson = serviceAccountJson;
        boolean validCredentials = false;
        try {
            JsonNode credentials = mapper.readTree(serviceAccountJson);
            validCredentials = !packageName.isBlank() && !monthlyProductId.isBlank() && !annualProductId.isBlank()
                    && !credentials.path("client_email").asText().isBlank()
                    && !credentials.path("private_key").asText().isBlank();
        } catch (Exception ignored) {
            // Invalid or missing credentials keep the app's Google Play purchase option disabled.
        }
        this.configured = validCredentials;
    }

    public boolean isConfigured() {
        return configured;
    }

    public String planForProduct(String productId) {
        if (monthlyProductId.equals(productId)) return "MONTHLY";
        if (annualProductId.equals(productId)) return "YEARLY";
        return null;
    }

    public ProductPurchase getProductPurchase(String productId, String purchaseToken) {
        URI uri = URI.create(API_ROOT + encode(packageName) + "/purchases/products/" + encode(productId)
                + "/tokens/" + encode(purchaseToken));
        JsonNode body = http.get().uri(uri).headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve().body(JsonNode.class);
        if (body == null) throw new IllegalStateException("Google Play returned an empty purchase response.");
        return new ProductPurchase(body.path("purchaseState").asInt(-1),
                body.path("consumptionState").asInt(-1), body.path("quantity").asInt(1),
                body.path("productId").asText(""), body.path("obfuscatedExternalAccountId").asText(""));
    }

    public void consumeProduct(String productId, String purchaseToken) {
        URI uri = URI.create(API_ROOT + encode(packageName) + "/purchases/products/" + encode(productId)
                + "/tokens/" + encode(purchaseToken) + ":consume");
        http.post().uri(uri).headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve().toBodilessEntity();
    }

    private String accessToken() {
        Instant now = Instant.now();
        if (cachedAccessToken != null && cachedAccessTokenExpiry.isAfter(now.plusSeconds(60))) return cachedAccessToken;
        synchronized (this) {
            now = Instant.now();
            if (cachedAccessToken != null && cachedAccessTokenExpiry.isAfter(now.plusSeconds(60))) return cachedAccessToken;
            if (!isConfigured()) throw new IllegalStateException("Google Play billing credentials are not configured.");
            try {
                JsonNode credentials = mapper.readTree(serviceAccountJson);
                String clientEmail = credentials.path("client_email").asText();
                String privateKeyPem = credentials.path("private_key").asText();
                if (clientEmail.isBlank() || privateKeyPem.isBlank()) throw new IllegalArgumentException("Invalid service account credentials.");

                long issuedAt = now.getEpochSecond();
                String header = mapper.writeValueAsString(mapper.createObjectNode().put("alg", "RS256")
                        .put("typ", "JWT").put("kid", credentials.path("private_key_id").asText()));
                String claims = mapper.writeValueAsString(mapper.createObjectNode()
                        .put("iss", clientEmail).put("scope", SCOPE).put("aud", "https://oauth2.googleapis.com/token")
                        .put("iat", issuedAt).put("exp", issuedAt + 3600));
                String assertionBase = base64Url(header) + "." + base64Url(claims);
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(loadPrivateKey(privateKeyPem));
                signer.update(assertionBase.getBytes(StandardCharsets.UTF_8));
                String assertion = assertionBase + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());

                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
                form.add("assertion", assertion);
                JsonNode token = http.post().uri("https://oauth2.googleapis.com/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
                if (token == null || token.path("access_token").asText().isBlank()) {
                    throw new IllegalStateException("Google OAuth did not return an access token.");
                }
                cachedAccessToken = token.path("access_token").asText();
                cachedAccessTokenExpiry = now.plusSeconds(Math.max(60, token.path("expires_in").asLong(3600)));
                return cachedAccessToken;
            } catch (Exception exception) {
                throw new IllegalStateException("Could not authenticate with Google Play Developer API.", exception);
            }
        }
    }

    private PrivateKey loadPrivateKey(String pem) throws Exception {
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    public record ProductPurchase(int purchaseState, int consumptionState, int quantity,
                                  String productId, String obfuscatedExternalAccountId) {}
}
