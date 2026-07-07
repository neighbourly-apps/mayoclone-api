package com.mayoclone.web;

import com.mayoclone.security.CurrentAccountService;
import com.mayoclone.service.ImageStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Authenticated image upload for menu-item photos. Stores under the tenant's
 * folder and returns the public URL path the static handler serves.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final ImageStorageService storage;
    private final CurrentAccountService currentAccount;

    public UploadController(ImageStorageService storage, CurrentAccountService currentAccount) {
        this.storage = storage;
        this.currentAccount = currentAccount;
    }

    /** {@code POST /api/uploads/image} — multipart field {@code file}. Returns 201 with the URL. */
    @PostMapping("/image")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        Long accountId = currentAccount.accountId();
        String url = storage.storeMenuImage(accountId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("url", url));
    }
}
