package com.goldentime.api.business;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Component
public class BusinessLicenseStorage {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path root;

    public BusinessLicenseStorage(@Value("${app.upload.private-dir:./private-uploads}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    public StoredLicense save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Vui lòng tải giấy phép kinh doanh lên.");
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Giấy phép kinh doanh vượt quá 10 MB.");

        try {
            byte[] bytes = file.getBytes();
            String contentType = detectType(bytes);
            String extension = extension(contentType);
            String name = UUID.randomUUID() + extension;
            Files.createDirectories(root);
            Files.write(root.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String original = file.getOriginalFilename();
            if (original == null) original = "giay-phep" + extension;
            else {
                original = original.replace('\\', '/');
                original = original.substring(original.lastIndexOf('/') + 1);
            }
            if (original.isBlank()) original = "giay-phep" + extension;
            if (original.length() > 255) original = original.substring(original.length() - 255);
            return new StoredLicense(name, original, contentType);
        } catch (IOException exception) {
            throw ApiException.unavailable("Không thể lưu giấy phép kinh doanh.");
        }
    }

    public Resource load(String storedName) {
        if (storedName == null || storedName.contains("/") || storedName.contains("\\")) {
            throw ApiException.notFound("Không tìm thấy giấy phép kinh doanh.");
        }
        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw ApiException.notFound("Không tìm thấy giấy phép kinh doanh.");
        }
        return new FileSystemResource(target);
    }

    public void delete(String storedName) {
        if (storedName == null || storedName.contains("/") || storedName.contains("\\")) return;
        try {
            Files.deleteIfExists(root.resolve(storedName).normalize());
        } catch (IOException ignored) {
            // A failed cleanup does not replace the original application error.
        }
    }

    private static String detectType(byte[] bytes) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return "application/pdf";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        throw ApiException.badRequest("Giấy phép kinh doanh phải là tệp PDF, PNG hoặc JPG hợp lệ.");
    }

    private static String extension(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
    }

    public record StoredLicense(String storedName, String originalName, String contentType) {}
}
