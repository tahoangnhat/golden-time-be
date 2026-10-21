package com.goldentime.api.articles;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Component
public class ArticleImageStorage {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final String CLOUDINARY_FOLDER = "goldentime/articles";

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final RestClient restClient = RestClient.create();

    public ArticleImageStorage(@Value("${app.upload.cloudinary.cloud-name:}") String cloudName,
                               @Value("${app.upload.cloudinary.api-key:}") String apiKey,
                               @Value("${app.upload.cloudinary.api-secret:}") String apiSecret) {
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
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
            String extension = switch (contentType) {
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
            long timestamp = Instant.now().getEpochSecond();
            String signature = sign(timestamp);

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "article." + extension;
                }
            }).contentType(MediaType.parseMediaType(contentType));
            builder.part("api_key", apiKey);
            builder.part("timestamp", Long.toString(timestamp));
            builder.part("folder", CLOUDINARY_FOLDER);
            builder.part("signature", signature);

            Map<?, ?> response = restClient.post()
                    .uri("https://api.cloudinary.com/v1_1/{cloudName}/image/upload", cloudName)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(Map.class);
            Object secureUrl = response == null ? null : response.get("secure_url");
            if (!(secureUrl instanceof String url) || !url.startsWith("https://")) {
                throw ApiException.unavailable("Cloudinary không trả về địa chỉ ảnh hợp lệ.");
            }
            return new StoredImage(url, contentType);
        } catch (IOException exception) {
            throw ApiException.badRequest("Không thể đọc tệp ảnh đã chọn.");
        } catch (RestClientResponseException exception) {
            throw ApiException.unavailable("Cloudinary chưa nhận được ảnh. Kiểm tra cấu hình dịch vụ lưu ảnh rồi thử lại.");
        } catch (RestClientException exception) {
            throw ApiException.unavailable("Không kết nối được với Cloudinary. Thử lại sau ít phút.");
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

    public record StoredImage(String url, String contentType) {}
}
