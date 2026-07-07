package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/uploads/image: image ok -> 201 + url, non-image -> 400, oversize -> 413, static serve. */
class UploadImageIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-upload";

    // 1x1 transparent PNG.
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82};

    @Test
    void imageUploadReturns201WithUrlAndIsServedBack() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG);

        MvcResult res = mvc.perform(multipart("/api/uploads/image").file(file)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").exists())
                .andReturn();

        Map<?, ?> body = json.readValue(res.getResponse().getContentAsString(), Map.class);
        String url = (String) body.get("url");
        assertTrue(url.startsWith("/uploads/menu/"), "url path: " + url);
        assertTrue(url.endsWith(".png"), "png extension: " + url);

        // The stored file is served back publicly (no auth) via the resource handler.
        mvc.perform(get(url))
                .andExpect(status().isOk());
    }

    @Test
    void nonImageIsRejectedWith400() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/uploads/image").file(file)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizeIsRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1]; // > 2MB
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooBig);

        mvc.perform(multipart("/api/uploads/image").file(file)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void uploadRequiresAuth() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", PNG);
        mvc.perform(multipart("/api/uploads/image").file(file)
                        .header("X-Forwarded-For", uniqueIp()))
                .andExpect(status().isUnauthorized());
    }
}
