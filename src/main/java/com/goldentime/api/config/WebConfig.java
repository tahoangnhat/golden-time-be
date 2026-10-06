package com.goldentime.api.config;

import com.goldentime.api.scan.UploadStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final UploadStorage uploadStorage;

    public WebConfig(UploadStorage uploadStorage) {
        this.uploadStorage = uploadStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploadStorage.resourceLocation());
    }
}
