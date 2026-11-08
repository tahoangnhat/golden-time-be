package com.goldentime.api.premium;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldentime.api.commerce.payment.PayOSClient;
import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.util.Base64;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class PremiumService {
    private static final String[] PROFILE_FIELDS = {
            "ageGroup", "heightCm", "weightKg", "allergies", "chronicConditions", "medications", "healthGoals"
    };

    private final JdbcTemplate jdbc;
    private final PayOSClient payOS;
    private final RestClient gemini;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final String geminiModel;
    private final boolean geminiConfigured;
    private final String healthEncryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String frontendUrl;
    private final int paymentExpiryMinutes;

    public PremiumService(JdbcTemplate jdbc, PayOSClient payOS, RestClient.Builder builder, ObjectMapper mapper,
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          @Value("${app.premium.gemini.api-key:}") String geminiKey,
                          @Value("${app.premium.gemini.model:gemini-3.1-flash-lite}") String geminiModel,
                          @Value("${app.premium.health-encryption-key:}") String healthEncryptionKey,
                          @Value("${app.payment.frontend-url:http://localhost:8080}") String frontendUrl,
                          @Value("${app.payment.payos.link-expiry-minutes:15}") int paymentExpiryMinutes) {
        this.jdbc = jdbc;
        this.payOS = payOS;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.geminiModel = geminiModel;
        this.geminiConfigured = geminiKey != null && !geminiKey.isBlank();
        this.healthEncryptionKey = healthEncryptionKey;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.paymentExpiryMinutes = Math.max(5, paymentExpiryMinutes);
        JdkClientHttpRequestFactory aiRequestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        aiRequestFactory.setReadTimeout(Duration.ofSeconds(30));
        RestClient.Builder aiBuilder = builder.clone()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .requestFactory(aiRequestFactory);
        if (geminiKey != null && !geminiKey.isBlank()) aiBuilder.defaultHeader("x-goog-api-key", geminiKey.trim());
        this.gemini = aiBuilder.build();
    }

    public Overview overview(long userId) {
        List<Instant> expiries = jdbc.query("SELECT expires_at FROM premium_memberships WHERE user_id = ?", (rs, row) -> rs.getTimestamp(1).toInstant(), userId);
        Instant expiresAt = expiries.isEmpty() ? null : expiries.get(0);
        HealthProfile profile = getHealthProfile(userId);
        return new Overview(priceVnd(), payOS.isConfigured(), geminiConfigured,
                expiresAt != null && expiresAt.isAfter(Instant.now()), expiresAt, profile);
    }

    public PaymentInfo createPayment(long userId) {
        long amount = priceVnd();
        if (amount <= 0) throw ApiException.unavailable("Gói Premium chưa được Admin cấu hình giá.");
        if (!payOS.isConfigured()) throw ApiException.unavailable("Thanh toán PayOS hiện chưa được cấu hình.");

        List<PaymentInfo> pending = jdbc.query("""
                SELECT order_code, amount_vnd, status, checkout_url, qr_code, expires_at
                FROM premium_payments
                WHERE user_id = ? AND status = 'PENDING' AND expires_at > NOW() AND checkout_url IS NOT NULL
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> new PaymentInfo(rs.getLong("order_code"), rs.getLong("amount_vnd"),
                rs.getString("status"), rs.getString("checkout_url"), rs.getString("qr_code"), rs.getTimestamp("expires_at").toInstant(), null), userId);
        if (!pending.isEmpty()) return pending.get(0);

        long orderCode = nextOrderCode();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(paymentExpiryMinutes));
        jdbc.update("INSERT INTO premium_payments (order_code, user_id, amount_vnd, expires_at) VALUES (?, ?, ?, ?)",
                orderCode, userId, amount, java.sql.Timestamp.from(expiresAt));
        PayOSClient.PaymentLink link;
        try {
            long expiresAtEpoch = expiresAt.getEpochSecond();
            link = payOS.createPaymentLink(orderCode, amount, "GoldenTime Premium",
                    frontendUrl + "/profile?payment=cancelled&orderCode=" + orderCode,
                    frontendUrl + "/profile?payment=returned&orderCode=" + orderCode,
                    expiresAtEpoch);
        } catch (RuntimeException exception) {
            jdbc.update("DELETE FROM premium_payments WHERE order_code = ? AND status = 'PENDING'", orderCode);
            throw exception;
        }
        jdbc.update("""
                UPDATE premium_payments SET payment_link_id = ?, checkout_url = ?, qr_code = ?, updated_at = NOW()
                WHERE order_code = ? AND status = 'PENDING'
                """, link.paymentLinkId(), link.checkoutUrl(), link.qrCode(), orderCode);
        return new PaymentInfo(orderCode, amount, "PENDING", link.checkoutUrl(), link.qrCode(), expiresAt, null);
    }

    public PaymentInfo paymentStatus(long userId, long orderCode) {
        PaymentInfo payment = loadPayment(userId, orderCode);
        if ("PENDING".equals(payment.status()) && payOS.isConfigured()) {
            PayOSClient.PaymentLinkStatus remote = payOS.getPaymentLinkStatus(orderCode);
            if ("PAID".equalsIgnoreCase(remote.status()) && remote.amountPaid() == payment.amountVnd()) {
                handlePayOSPayment(orderCode, remote.amountPaid(), null, null);
            } else if (remote.amountPaid() == 0 && isClosed(remote.status())) {
                jdbc.update("UPDATE premium_payments SET status = ?, updated_at = NOW() WHERE order_code = ? AND status = 'PENDING'",
                        "EXPIRED".equalsIgnoreCase(remote.status()) ? "EXPIRED" : "CANCELLED", orderCode);
            }
            return loadPayment(userId, orderCode);
        }
        return payment;
    }

    private PaymentInfo loadPayment(long userId, long orderCode) {
        List<PaymentInfo> rows = jdbc.query("""
                SELECT order_code, amount_vnd, status, checkout_url, qr_code, expires_at, paid_at
                FROM premium_payments WHERE order_code = ? AND user_id = ?
                """, (rs, row) -> new PaymentInfo(rs.getLong("order_code"), rs.getLong("amount_vnd"),
                rs.getString("status"), rs.getString("checkout_url"), rs.getString("qr_code"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant()), orderCode, userId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy thanh toán Premium.");
        return rows.get(0);
    }

    /** Called only after the shared PayOS webhook signature and success codes have been verified. */
    public boolean handlePayOSPayment(long orderCode, long amount, String reference, String paymentLinkId) {
        List<Long> matches = jdbc.query("SELECT amount_vnd FROM premium_payments WHERE order_code = ?", (rs, row) -> rs.getLong(1), orderCode);
        if (matches.isEmpty()) return false;
        long expectedAmount = matches.get(0);
        if (amount != expectedAmount) return true;
        transactions.execute(status -> {
            List<Long> userIds = jdbc.query("""
                    UPDATE premium_payments
                    SET status = 'PAID', paid_at = COALESCE(paid_at, NOW()), reference = COALESCE(?, reference),
                        payment_link_id = COALESCE(?, payment_link_id), updated_at = NOW()
                    WHERE order_code = ? AND amount_vnd = ? AND status = 'PENDING'
                    RETURNING user_id
                    """, (rs, row) -> rs.getLong(1), reference, paymentLinkId, orderCode, expectedAmount);
            if (!userIds.isEmpty()) {
                jdbc.update("""
                        INSERT INTO premium_memberships (user_id, expires_at, last_payment_order_code, updated_at)
                        VALUES (?, NOW() + INTERVAL '30 days', ?, NOW())
                        ON CONFLICT (user_id) DO UPDATE SET
                          expires_at = GREATEST(premium_memberships.expires_at, NOW()) + INTERVAL '30 days',
                          last_payment_order_code = EXCLUDED.last_payment_order_code,
                          updated_at = NOW()
                        """, userIds.get(0), orderCode);
            }
            return null;
        });
        return true;
    }

    public HealthProfile getHealthProfile(long userId) {
        List<HealthProfile> profiles = jdbc.query("""
                SELECT encrypted_profile
                FROM premium_health_profiles WHERE user_id = ?
                """, (rs, row) -> decryptProfile(rs.getString("encrypted_profile")), userId);
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public HealthProfile saveHealthProfile(long userId, ProfilePayload payload) {
        if (getHealthProfile(userId) == null) requirePremium(userId);
        if (payload == null || !Boolean.TRUE.equals(payload.consentToSave())) {
            throw ApiException.badRequest("Cần xác nhận đồng ý lưu hồ sơ sức khỏe.");
        }
        ProfilePayload clean = sanitize(payload);
        HealthProfile profile = new HealthProfile(clean.ageGroup(), clean.heightCm(), clean.weightKg(), clean.allergies(),
                clean.chronicConditions(), clean.medications(), clean.healthGoals(), clean.notProvidedFields());
        jdbc.update("""
                INSERT INTO premium_health_profiles (user_id, encrypted_profile, consent_at, updated_at)
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (user_id) DO UPDATE SET encrypted_profile = EXCLUDED.encrypted_profile,
                    consent_at = NOW(), updated_at = NOW()
                """, userId, encryptProfile(profile));
        return profile;
    }

    public void deleteHealthProfile(long userId) {
        jdbc.update("DELETE FROM premium_health_profiles WHERE user_id = ?", userId);
    }

    public RecommendationResponse recommend(long userId, RecommendationRequest request) {
        if (request == null || (geminiConfigured && !Boolean.TRUE.equals(request.consentToAi()))) {
            throw ApiException.badRequest("Bạn cần đồng ý gửi thông tin sức khỏe tối thiểu tới Gemini để nhận gợi ý AI.");
        }
        requirePremium(userId);
        ProfilePayload profile = sanitize(request.profile() == null ? new ProfilePayload(null, null, null, null, null, null, null, List.of(), false) : request.profile());
        if (Boolean.TRUE.equals(request.saveProfileConsent())) saveHealthProfile(userId, new ProfilePayload(
                profile.ageGroup(), profile.heightCm(), profile.weightKg(), profile.allergies(), profile.chronicConditions(),
                profile.medications(), profile.healthGoals(), profile.notProvidedFields(), true));

        List<Candidate> eligible = candidates(profile);
        if (eligible.isEmpty()) {
            return new RecommendationResponse(List.of(), false,
                    "Hiện chưa có sản phẩm phù hợp đang còn hàng. Bạn có thể thử lại sau.", safetyNote());
        }
        List<Candidate> ranked = eligible.stream().sorted(Comparator.comparingDouble((Candidate item) -> score(item, profile)).reversed()
                .thenComparingLong(Candidate::pricePerKg)).limit(12).toList();
        List<Long> aiProductIds = askGemini(profile, ranked);
        boolean aiEnhanced = !aiProductIds.isEmpty();
        List<RecommendationItem> recommendations;
        if (aiEnhanced) {
            Set<Long> allowed = new HashSet<>();
            ranked.forEach(item -> allowed.add(item.productId()));
            Set<Long> seen = new HashSet<>();
            recommendations = aiProductIds.stream().filter(id -> allowed.contains(id) && seen.add(id))
                    .map(id -> ranked.stream().filter(candidate -> candidate.productId() == id).findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .limit(4)
                    .map(this::toItem)
                    .toList();
        } else {
            recommendations = ranked.stream().limit(4).map(this::toItem).toList();
        }
        if (recommendations.isEmpty()) recommendations = ranked.stream().limit(4).map(this::toItem).toList();
        return new RecommendationResponse(recommendations, aiEnhanced,
                aiEnhanced ? "Đề xuất được xếp hạng bằng quy tắc dinh dưỡng và AI." : "Đang dùng quy tắc dinh dưỡng; AI chưa cấu hình hoặc tạm thời không phản hồi.", safetyNote());
    }

    private List<Candidate> candidates(ProfilePayload profile) {
        List<Candidate> all = jdbc.query("""
                SELECT p.id, p.display_name, COALESCE(f.name, p.display_name) AS fruit_name,
                       COALESCE(f.emoji, '🍎') AS emoji, s.name AS shop_name, p.price_per_kg, p.stock_kg,
                       n.calories_per_100g, n.vitamin_c_mg, n.fiber_g, n.sugar_g, n.potassium_mg, n.water_percent,
                       n.health_benefits, n.serving_note
                FROM shop_products p
                JOIN shops s ON s.id = p.shop_id
                LEFT JOIN fruits f ON f.id = p.fruit_id
                LEFT JOIN fruit_nutrition n ON n.fruit_id = f.id
                WHERE p.status = 'ON_SALE' AND p.stock_kg > 0 AND s.status = 'ACTIVE' AND p.fruit_id IS NOT NULL
                ORDER BY p.display_name, p.price_per_kg
                """, (rs, row) -> new Candidate(rs.getLong("id"), rs.getString("display_name"),
                rs.getString("fruit_name"), rs.getString("emoji"), rs.getString("shop_name"), rs.getLong("price_per_kg"),
                rs.getBigDecimal("stock_kg"), rs.getBigDecimal("calories_per_100g"), rs.getBigDecimal("vitamin_c_mg"),
                rs.getBigDecimal("fiber_g"), rs.getBigDecimal("sugar_g"), rs.getBigDecimal("potassium_mg"),
                rs.getBigDecimal("water_percent"), rs.getString("health_benefits"), rs.getString("serving_note")));
        List<String> allergies = splitTerms(profile.allergies());
        return all.stream().filter(candidate -> allergies.stream().noneMatch(term -> containsNormalized(candidate.fruitName(), term)
                || containsNormalized(candidate.displayName(), term))).toList();
    }

    private List<Long> askGemini(ProfilePayload profile, List<Candidate> candidates) {
        if (candidates.isEmpty() || !geminiConfigured) return List.of();
        try {
            List<Map<String, Object>> candidateInput = candidates.stream().map(item -> {
                Map<String, Object> candidate = new HashMap<>();
                candidate.put("productId", item.productId());
                candidate.put("fruit", item.fruitName());
                candidate.put("product", item.displayName());
                candidate.put("caloriesPer100g", nullableNumber(item.caloriesPer100g()));
                candidate.put("vitaminCMg", nullableNumber(item.vitaminCMg()));
                candidate.put("fiberG", nullableNumber(item.fiberG()));
                candidate.put("sugarG", nullableNumber(item.sugarG()));
                candidate.put("waterPercent", nullableNumber(item.waterPercent()));
                candidate.put("nutritionNotes", blankToEmpty(item.healthBenefits()));
                candidate.put("servingNote", blankToEmpty(item.servingNote()));
                return candidate;
            }).toList();
            var profileInput = mapper.createObjectNode();
            if (profile.ageGroup() != null) profileInput.put("ageGroup", profile.ageGroup());
            if (profile.heightCm() != null) profileInput.put("heightCm", profile.heightCm());
            if (profile.weightKg() != null) profileInput.put("weightKg", profile.weightKg());
            if (profile.allergies() != null) profileInput.put("allergies", profile.allergies());
            if (profile.chronicConditions() != null) profileInput.put("chronicConditions", profile.chronicConditions());
            if (profile.medications() != null) profileInput.put("medications", profile.medications());
            if (profile.healthGoals() != null) profileInput.put("healthGoals", profile.healthGoals());
            String userInput = "Hồ sơ sức khỏe tự khai báo (chỉ gồm trường bạn đã cung cấp): " + mapper.writeValueAsString(profileInput)
                    + "\nSản phẩm đang bán và còn hàng (chỉ được chọn productId trong danh sách): " + mapper.writeValueAsString(candidateInput);
            var body = mapper.createObjectNode();
            body.put("model", geminiModel);
            body.put("store", false);
            body.put("system_instruction", "Chọn tối đa 4 productId từ danh sách để cá nhân hóa lựa chọn trái cây theo mục tiêu dinh dưỡng phổ thông và dữ liệu dinh dưỡng có sẵn. Tránh trùng loại trái cây nếu có thể. Không chẩn đoán, điều trị, khẳng định an toàn hay suy diễn tương tác thuốc/chống chỉ định. Nếu thông tin về bệnh nền hoặc thuốc được cung cấp, không dùng chúng để tuyên bố một loại trái cây an toàn hay không an toàn. Chỉ trả các productId đã cho theo JSON schema.");
            body.put("input", userInput);
            var format = mapper.createObjectNode();
            format.put("type", "text");
            format.put("mime_type", "application/json");
            var schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            var props = schema.putObject("properties");
            var items = props.putObject("productIds");
            items.put("type", "array");
            items.putObject("items").put("type", "integer");
            schema.putArray("required").add("productIds");
            format.set("schema", schema);
            body.set("response_format", format);
            body.putObject("generation_config").put("max_output_tokens", 128);

            JsonNode response = gemini.post().uri("/interactions").body(body).retrieve().body(JsonNode.class);
            if (!"completed".equals(response.path("status").asText())) return List.of();
            StringBuilder output = new StringBuilder();
            response.path("steps").forEach(step -> {
                if ("model_output".equals(step.path("type").asText())) {
                    step.path("content").forEach(part -> {
                        if ("text".equals(part.path("type").asText())) output.append(part.path("text").asText());
                    });
                }
            });
            String content = output.toString();
            if (content.isBlank()) return List.of();
            JsonNode result = mapper.readTree(content);
            List<Long> selectedIds = new ArrayList<>();
            result.path("productIds").forEach(item -> {
                long id = item.asLong(-1);
                if (id > 0) selectedIds.add(id);
            });
            return selectedIds;
        } catch (RestClientException exception) {
            return List.of();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private RecommendationItem toItem(Candidate candidate) {
        return new RecommendationItem(candidate.productId(), candidate.displayName(), candidate.fruitName(), candidate.emoji(),
                candidate.shopName(), candidate.pricePerKg(), candidate.stockKg(),
                fallbackReason(candidate),
                "Kiểm tra thông tin dị ứng, thành phần và nguy cơ lẫn chéo với cửa hàng trước khi mua.",
                "/shop/" + candidate.productId());
    }

    private double score(Candidate item, ProfilePayload profile) {
        String goal = normalize(profile.healthGoals());
        double fiber = number(item.fiberG());
        double sugar = number(item.sugarG());
        double water = number(item.waterPercent());
        double vitaminC = number(item.vitaminCMg());
        if (goal.contains("duong huyet") || goal.contains("it duong") || goal.contains("kiem soat can nang"))
            return item.sugarG() == null ? -1 : 1.0 / (1.0 + sugar);
        if (goal.contains("tieu hoa") || goal.contains("chat xo")) return fiber;
        if (goal.contains("bu nuoc") || goal.contains("hydration")) return water;
        if (goal.contains("vitamin c") || goal.contains("mien dich")) return vitaminC;
        return (fiber * 0.35) + (water * 0.02) + (vitaminC * 0.03) - (sugar * 0.08);
    }

    private String fallbackReason(Candidate item) {
        if (item.fiberG() != null && item.fiberG().compareTo(BigDecimal.ZERO) > 0)
            return "Có dữ liệu chất xơ trong bảng dinh dưỡng; đây là thông tin tham khảo cho lựa chọn đa dạng hơn.";
        if (item.waterPercent() != null) return "Có dữ liệu hàm lượng nước trong bảng dinh dưỡng; cân nhắc kết hợp với chế độ ăn đa dạng.";
        return "Sản phẩm trái cây đang được cửa hàng bán và còn hàng; dữ liệu dinh dưỡng chi tiết hiện còn hạn chế.";
    }

    private void requirePremium(long userId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM premium_memberships WHERE user_id = ? AND expires_at > NOW()", Integer.class, userId);
        if (active == null || active == 0) throw ApiException.forbidden("Tính năng gợi ý sức khỏe cần gói Premium còn hiệu lực.");
    }

    private long priceVnd() {
        return jdbc.query("SELECT setting_value FROM platform_settings WHERE setting_key = 'premium_price_vnd'",
                rs -> rs.next() ? parsePrice(rs.getString(1)) : 0L);
    }

    private long nextOrderCode() {
        for (int attempt = 0; attempt < 30; attempt++) {
            long candidate = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
            Integer exists = jdbc.queryForObject("""
                    SELECT (EXISTS (SELECT 1 FROM premium_payments WHERE order_code = ?) OR
                            EXISTS (SELECT 1 FROM orders WHERE id = ?))::int
                    """, Integer.class, candidate, candidate);
            if (exists == null || exists == 0) return candidate;
        }
        throw ApiException.unavailable("Không tạo được mã thanh toán. Vui lòng thử lại.");
    }

    private ProfilePayload sanitize(ProfilePayload value) {
        List<String> declined = value.notProvidedFields() == null ? List.of() : value.notProvidedFields().stream()
                .filter(field -> field != null && List.of(PROFILE_FIELDS).contains(field)).distinct().toList();
        BigDecimal height = declined.contains("heightCm") ? null : value.heightCm();
        BigDecimal weight = declined.contains("weightKg") ? null : value.weightKg();
        validateNumber(height, new BigDecimal("30"), new BigDecimal("250"), "Chiều cao cần trong khoảng 30–250 cm.");
        validateNumber(weight, new BigDecimal("1"), new BigDecimal("500"), "Cân nặng cần trong khoảng 1–500 kg.");
        return new ProfilePayload(
                declined.contains("ageGroup") ? null : trimText(value.ageGroup(), 32), height, weight,
                declined.contains("allergies") ? null : trimText(value.allergies(), 2000),
                declined.contains("chronicConditions") ? null : trimText(value.chronicConditions(), 2000),
                declined.contains("medications") ? null : trimText(value.medications(), 2000),
                declined.contains("healthGoals") ? null : trimText(value.healthGoals(), 1000), declined, value.consentToSave());
    }

    private void validateNumber(BigDecimal value, BigDecimal min, BigDecimal max, String message) {
        if (value != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0)) throw ApiException.badRequest(message);
    }

    private String encryptProfile(HealthProfile profile) {
        try {
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, healthKey(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(profile));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("Không thể mã hóa hồ sơ sức khỏe. Kiểm tra PREMIUM_HEALTH_ENCRYPTION_KEY.");
        }
    }

    private HealthProfile decryptProfile(String value) {
        try {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException("Unsupported encrypted profile");
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, healthKey(), new GCMParameterSpec(128, nonce));
            return mapper.readValue(cipher.doFinal(encrypted), HealthProfile.class);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("Không thể giải mã hồ sơ sức khỏe. Kiểm tra PREMIUM_HEALTH_ENCRYPTION_KEY.");
        }
    }

    private SecretKeySpec healthKey() {
        try {
            byte[] key = Base64.getDecoder().decode(healthEncryptionKey == null ? "" : healthEncryptionKey.trim());
            if (key.length != 32) throw new IllegalArgumentException("Expected 32 bytes");
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw ApiException.unavailable("Cần cấu hình PREMIUM_HEALTH_ENCRYPTION_KEY bằng khóa Base64 32 byte để lưu hồ sơ sức khỏe.");
        }
    }

    private List<String> splitTerms(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("[,;\\n]"))
                .map(String::trim).filter(term -> term.length() >= 2)
                .map(PremiumService::normalize)
                .map(term -> term.replaceFirst("^(toi\\s+)?(bi\\s+)?di ung(\\s+voi)?\\s*:?\\s*", "").trim())
                .filter(term -> term.length() >= 2).toList();
    }

    private boolean containsNormalized(String haystack, String needle) {
        return !needle.isBlank() && normalize(haystack).contains(needle);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    private static Double nullableNumber(BigDecimal value) { return value == null ? null : value.doubleValue(); }
    private static double number(BigDecimal value) { return value == null ? 0 : value.doubleValue(); }
    private static String blankToEmpty(String value) { return value == null ? "" : value; }
    private static String trimText(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= max ? (clean.isEmpty() ? null : clean) : clean.substring(0, max);
    }
    private static long parsePrice(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException exception) { return 0; }
    }
    private static boolean isClosed(String status) { return "CANCELLED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status); }
    private static String safetyNote() {
        return "Thông tin chỉ mang tính tham khảo dinh dưỡng, không thay thế chẩn đoán hay điều trị. Hãy hỏi bác sĩ/dược sĩ khi có bệnh nền, đang dùng thuốc hoặc có dị ứng; xác nhận thành phần và nguy cơ lẫn chéo với cửa hàng.";
    }

    public record Overview(long priceVnd, boolean payosConfigured, boolean geminiConfigured, boolean premiumActive,
                           Instant expiresAt, HealthProfile healthProfile) {}
    public record PaymentInfo(long orderCode, long amountVnd, String status, String checkoutUrl, String qrCode, Instant expiresAt, Instant paidAt) {}
    public record ProfilePayload(String ageGroup, BigDecimal heightCm, BigDecimal weightKg, String allergies,
                                 String chronicConditions, String medications, String healthGoals,
                                 List<String> notProvidedFields, Boolean consentToSave) {}
    public record HealthProfile(String ageGroup, BigDecimal heightCm, BigDecimal weightKg, String allergies,
                                String chronicConditions, String medications, String healthGoals,
                                List<String> notProvidedFields) {}
    public record RecommendationRequest(ProfilePayload profile, Boolean consentToAi, Boolean saveProfileConsent) {}
    public record RecommendationResponse(List<RecommendationItem> recommendations, boolean aiEnhanced, String message, String safetyNote) {}
    public record RecommendationItem(long productId, String displayName, String fruitName, String emoji, String shopName,
                                     long pricePerKg, BigDecimal stockKg, String reason, String caution, String productUrl) {}
    private record Candidate(long productId, String displayName, String fruitName, String emoji, String shopName,
                             long pricePerKg, BigDecimal stockKg, BigDecimal caloriesPer100g, BigDecimal vitaminCMg,
                             BigDecimal fiberG, BigDecimal sugarG, BigDecimal potassiumMg, BigDecimal waterPercent,
                             String healthBenefits, String servingNote) {}
}
