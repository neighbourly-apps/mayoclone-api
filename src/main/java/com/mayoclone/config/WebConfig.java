package com.mayoclone.config;

import com.mayoclone.service.ImageStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded files (menu-item photos) from the configured upload directory.
 * {@code /uploads/**} is mapped to {@code file:<absolute upload dir>/}; the GET
 * route is made public in {@link com.mayoclone.security.SecurityConfig}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ImageStorageService imageStorage;

    public WebConfig(ImageStorageService imageStorage) {
        this.imageStorage = imageStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A trailing slash is required for the handler to treat the file: URL as a
        // directory. toUri() only appends one when the dir already exists, so force it.
        String location = imageStorage.uploadRoot().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
