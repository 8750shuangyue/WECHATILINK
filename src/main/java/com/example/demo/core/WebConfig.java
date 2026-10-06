package com.example.demo.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.demo.config.WebAuthInterceptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebAuthInterceptor webAuthInterceptor;
    private final String[] allowedOrigins;

    public WebConfig(WebAuthInterceptor webAuthInterceptor,
                     @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                     String allowedOrigins) {
        this.webAuthInterceptor = webAuthInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);

        if (this.allowedOrigins.length == 0 || Arrays.asList(this.allowedOrigins).contains("*")) {
            throw new IllegalArgumentException("app.cors.allowed-origins must contain explicit origins");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(webAuthInterceptor)
                .addPathPatterns("/api/**", "/uploads/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get("./uploads");
        String uploadPathStr = uploadPath.toAbsolutePath().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPathStr + "/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Accept", "Authorization", "Content-Type", "Origin", "X-Requested-With")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
