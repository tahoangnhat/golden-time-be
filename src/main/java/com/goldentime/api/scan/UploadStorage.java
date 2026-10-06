package com.goldentime.api.scan;

import com.goldentime.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
public class UploadStorage {
    private final Path root;

    public UploadStorage(@Value("${app.upload.dir:./uploads}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    public String save(MultipartFile file) {
        try {
            Files.createDirectories(root);
            String extension = extension(file.getContentType());
            String filename = UUID.randomUUID() + extension;
            Files.copy(file.getInputStream(), root.resolve(filename));
            return filename;
        } catch (IOException exception) {
            throw ApiException.unavailable("Không thể lưu ảnh quét.");
        }
    }

    public String resourceLocation() {
        return root.toUri().toString();
    }

    private static String extension(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
