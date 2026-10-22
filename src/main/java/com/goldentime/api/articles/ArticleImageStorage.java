package com.goldentime.api.articles;

import com.goldentime.api.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Component
public class ArticleImageStorage {
    private static final Logger log = LoggerFactory.getLogger(ArticleImageStorage.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String CLOUDINARY_FOLDER = "goldentime/articles";
    private static final ParameterizedTypeReference<Map<String, Object>> CLOUDINARY_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final RestClient restClient = RestClient.create();

    public ArticleImageStorage(@Value("${app.upload.cloudinary.cloud-name:}") String cloudName,
                               @Value("${app.upload.cloudinary.api-key:}") String apiKey,
                               @Value("${app.upload.cloudinary.api-secret:}") String apiSecret) {
        this.cloudName = trim(cloudName);
        this.apiKey = trim(apiKey);
        this.apiSecret = trim(apiSecret);
    }

    public StoredImage save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Chọn ảnh để tải lên.");
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh vượt quá 10 MB.");
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw ApiException.unavailable("Chưa cấu hình dịch vụ lưu ảnh Cloudinary cho backend.");
        }

        try {
            byte[] bytes = file.getBytes();
            String contentType = detectType(bytes);
            long timestamp = Instant.now().getEpochSecond();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("file", "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes));
            form.add("api_key", apiKey);
            form.add("timestamp", Long.toString(timestamp));
            form.add("folder", CLOUDINARY_FOLDER);
            form.add("signature", sign(timestamp));

            Map<String, Object> response = restClient.post()
                    .uri("https://api.cloudinary.com/v1_1/{cloudName}/image/upload", cloudName)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(CLOUDINARY_RESPONSE);
            Object secureUrl = response == null ? null : response.get("secure_url");
            if (!(secureUrl instanceof String url) || !url.startsWith("https://")) {
                throw ApiException.unavailable("Cloudinary không trả về địa chỉ ảnh hợp lệ.");
            }
            return new StoredImage(url, contentType);
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ApiException.badRequest("Không thể đọc tệp ảnh đã chọn.");
        } catch (RestClientResponseException exception) {
            log.warn("Cloudinary rejected article image upload: {} {}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw ApiException.unavailable("Cloudinary chưa nhận được ảnh. Kiểm tra CLOUDINARY_CLOUD_NAME, API key và API secret rồi thử lại.");
        } catch (RestClientException exception) {
            log.warn("Could not reach Cloudinary for article image upload", exception);
            throw ApiException.unavailable("Không kết nối được với Cloudinary. Thử lại sau ít phút.");
        } catch (RuntimeException exception) {
            log.error("Unexpected article image upload failure", exception);
            throw ApiException.unavailable("Không tải được ảnh lên Cloudinary. Thử lại hoặc dán URL HTTPS công khai.");
        }
    }

    private String sign(long timestamp) {
        String valueToSign = "folder=" + CLOUDINARY_FOLDER + "&timestamp=" + timestamp + apiSecret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(valueToSign.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required by the Java runtime.", exception);
        }
    }

    private static String detectType(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        throw ApiException.badRequest("Ảnh phải là PNG, JPG hoặc WebP hợp lệ.");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record StoredImage(String url, String contentType) {}
}
