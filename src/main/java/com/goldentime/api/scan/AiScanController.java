package com.goldentime.api.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldentime.api.common.ApiException;
import com.goldentime.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/scans")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AiScanController {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final FruitAnalyzerClient analyzer;
    private final UploadStorage uploadStorage;

    public AiScanController(JdbcTemplate jdbc, ObjectMapper objectMapper,
                            FruitAnalyzerClient analyzer, UploadStorage uploadStorage) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.analyzer = analyzer;
        this.uploadStorage = uploadStorage;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ScanResponse analyze(
            @RequestPart("file") MultipartFile image,
            @RequestParam(required = false) Long shopId,
            Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        if (image.isEmpty()) throw ApiException.badRequest("Vui lòng chọn ảnh trái cây.");
        if (image.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh vượt quá giới hạn 10 MB.");
        byte[] imageBytes = readBytes(image);
        String contentType = supportedImageType(image.getContentType(), imageBytes);
        if (shopId != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM shops WHERE id = ? AND status = 'ACTIVE'", Integer.class, shopId);
            if (count == null || count == 0) throw ApiException.notFound("Không tìm thấy cửa hàng đang hoạt động.");
        }

        String filename = "fruit" + extension(contentType);
        FruitAnalyzerClient.AnalysisResult analyzed = analyzer.analyze(imageBytes, filename, contentType);
        FruitAnalysis result = validate(analyzed.analysis());
        String imageName = uploadStorage.save(image);
        Long fruitId = findFruitId(result.fruitName());
        String batchCode = findBatchCode(fruitId, shopId);
        String rawJson = toJson(result);
        Long scanId = jdbc.queryForObject("""
                INSERT INTO ai_scans (user_id, image_path, fruit_id, detected_name, quality_score,
                    freshness_score, ripeness_label, sweetness_label, use_within, quality_warning,
                    suggestion, raw_response, shop_id, confidence, analysis_mode, batch_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, imageName, fruitId, result.fruitName(), result.qualityScore(),
                result.freshnessScore(), result.ripenessLabel(), result.sweetnessLabel(), result.useWithin(),
                result.qualityWarning(), result.suggestion(), rawJson, shopId, result.confidence(), analyzed.mode(), batchCode);
        return new ScanResponse(scanId, fruitId, result.fruitName(), result.qualityScore(), result.freshnessScore(),
                result.ripenessLabel(), result.sweetnessLabel(), result.useWithin(), result.qualityWarning(),
                result.suggestion(), result.confidence(), analyzed.mode(), "/uploads/" + imageName, batchCode, Instant.now());
    }

    @GetMapping("/mine")
    public List<ScanResponse> mine(Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        return jdbc.query("""
                SELECT a.id, a.fruit_id, COALESCE(a.detected_name, f.name) AS fruit_name,
                       a.quality_score, a.freshness_score, a.ripeness_label, a.sweetness_label,
                       a.use_within, a.quality_warning, a.suggestion, a.confidence, a.analysis_mode,
                       a.image_path, a.batch_code, a.created_at
                FROM ai_scans a LEFT JOIN fruits f ON f.id = a.fruit_id
                WHERE a.user_id = ? ORDER BY a.created_at DESC LIMIT 50
                """, (rs, row) -> new ScanResponse(rs.getLong("id"), nullableLong(rs, "fruit_id"),
                rs.getString("fruit_name"), nullableInt(rs, "quality_score"), nullableInt(rs, "freshness_score"),
                rs.getString("ripeness_label"), rs.getString("sweetness_label"), rs.getString("use_within"),
                rs.getString("quality_warning"), rs.getString("suggestion"), nullableDouble(rs, "confidence"),
                rs.getString("analysis_mode"), imageUrl(rs.getString("image_path")), rs.getString("batch_code"),
                rs.getTimestamp("created_at").toInstant()), userId);
    }

    @GetMapping("/{id}")
    public ScanResponse get(@PathVariable long id, Authentication authentication) {
        long userId = CurrentUser.id(authentication);
        List<ScanResponse> scans = jdbc.query("""
                SELECT a.id, a.user_id, a.fruit_id, COALESCE(a.detected_name, f.name) AS fruit_name,
                       a.quality_score, a.freshness_score, a.ripeness_label, a.sweetness_label,
                       a.use_within, a.quality_warning, a.suggestion, a.confidence, a.analysis_mode,
                       a.image_path, a.batch_code, a.created_at
                FROM ai_scans a LEFT JOIN fruits f ON f.id = a.fruit_id
                WHERE a.id = ? AND a.user_id = ?
                """, (rs, row) -> new ScanResponse(rs.getLong("id"), nullableLong(rs, "fruit_id"),
                rs.getString("fruit_name"), nullableInt(rs, "quality_score"), nullableInt(rs, "freshness_score"),
                rs.getString("ripeness_label"), rs.getString("sweetness_label"), rs.getString("use_within"),
                rs.getString("quality_warning"), rs.getString("suggestion"), nullableDouble(rs, "confidence"),
                rs.getString("analysis_mode"), imageUrl(rs.getString("image_path")), rs.getString("batch_code"),
                rs.getTimestamp("created_at").toInstant()), id, userId);
        if (scans.isEmpty()) throw ApiException.notFound("Không tìm thấy lượt quét.");
        return scans.get(0);
    }

    private Long findFruitId(String detectedName) {
        if (detectedName == null || detectedName.isBlank()) return null;
        List<Long> ids = jdbc.query("""
                SELECT id FROM fruits
                WHERE LOWER(?) LIKE '%' || LOWER(name) || '%'
                   OR LOWER(name) LIKE '%' || LOWER(?) || '%'
                   OR LOWER(COALESCE(search_keywords, '')) LIKE '%' || LOWER(?) || '%'
                ORDER BY LENGTH(name) DESC LIMIT 1
                """, (rs, row) -> rs.getLong(1), detectedName, detectedName, detectedName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findBatchCode(Long fruitId, Long shopId) {
        if (fruitId == null) return null;
        List<String> codes;
        if (shopId != null) {
            codes = jdbc.query("""
                    SELECT batch_code FROM shop_products
                    WHERE fruit_id = ? AND shop_id = ? AND batch_code IS NOT NULL
                    ORDER BY id DESC LIMIT 1
                    """, (rs, row) -> rs.getString(1), fruitId, shopId);
            if (!codes.isEmpty()) return codes.get(0);
        }
        codes = jdbc.query("SELECT batch_code FROM shop_products WHERE fruit_id = ? AND batch_code IS NOT NULL ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), fruitId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    private FruitAnalysis validate(FruitAnalysis result) {
        if (result == null || result.fruitName() == null || result.fruitName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Dịch vụ phân tích ảnh chưa nhận diện được loại trái cây.");
        }
        score(result.qualityScore(), "qualityScore");
        score(result.freshnessScore(), "freshnessScore");
        if (result.confidence() != null && (result.confidence() < 0 || result.confidence() > 1)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Dịch vụ AI trả về confidence ngoài khoảng 0–1.");
        }
        return result;
    }

    private static void score(Integer value, String name) {
        if (value != null && (value < 0 || value > 100)) throw new ApiException(HttpStatus.BAD_GATEWAY, "Dịch vụ AI trả về " + name + " ngoài khoảng 0–100.");
    }

    private String toJson(FruitAnalysis analysis) {
        try {
            return objectMapper.writeValueAsString(analysis);
        } catch (JsonProcessingException exception) {
            throw ApiException.unavailable("Không thể lưu kết quả phân tích.");
        }
    }

    private static String supportedImageType(String declaredType, byte[] bytes) {
        boolean png = bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        boolean webp = bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        String actual = png ? "image/png" : jpeg ? "image/jpeg" : webp ? "image/webp" : null;
        if (actual == null) throw ApiException.badRequest("Tệp tải lên không phải ảnh PNG, JPEG hoặc WebP hợp lệ.");
        if (declaredType != null && !declaredType.equalsIgnoreCase(actual)) {
            throw ApiException.badRequest("Định dạng ảnh không khớp với nội dung tệp.");
        }
        return actual;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException exception) {
            throw ApiException.badRequest("Không đọc được ảnh đã tải lên.");
        }
    }

    private static String imageUrl(String path) {
        return path == null ? null : "/uploads/" + path;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private record ScanResponse(long id, Long fruitId, String fruitName, Integer qualityScore, Integer freshnessScore,
                                String ripenessLabel, String sweetnessLabel, String useWithin, String qualityWarning,
                                String suggestion, Double confidence, String analysisMode, String imageUrl,
                                String batchCode, Instant createdAt) {}
}
