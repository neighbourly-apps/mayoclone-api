package com.mayoclone.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * Stores uploaded menu-item images on the local filesystem under
 * {@code <upload.dir>/menu/<accountId>/<uuid>.<ext>} and returns the public URL
 * path ({@code /uploads/menu/...}) that the static resource handler serves.
 *
 * <p>Only raster web image types are accepted; the size cap is also enforced by
 * Spring's multipart limits, but validated here too for a clean 400 (rather than
 * relying solely on the container's 413).
 */
@Service
public class ImageStorageService {

    /** Hard cap in bytes (2 MB), matching {@code spring.servlet.multipart.max-file-size}. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** Allowed content types -> file extension. */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    private final Path uploadRoot;

    public ImageStorageService(@Value("${mayoclone.upload.dir:uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /** Absolute root directory that the static resource handler maps {@code /uploads/**} to. */
    public Path uploadRoot() {
        return uploadRoot;
    }

    /**
     * Validate + persist an uploaded image for the given tenant, returning the
     * public URL path ({@code /uploads/menu/<accountId>/<uuid>.<ext>}).
     */
    public String storeMenuImage(Long accountId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds 2MB limit");
        }
        String contentType = file.getContentType();
        String ext = contentType == null ? null : ALLOWED.get(contentType.toLowerCase());
        if (ext == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported content type; expected png/jpeg/webp/gif");
        }

        String fileName = UUID.randomUUID() + "." + ext;
        Path dir = uploadRoot.resolve("menu").resolve(String.valueOf(accountId)).normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            file.transferTo(target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to store upload", e);
        }
        return "/uploads/menu/" + accountId + "/" + fileName;
    }
}
