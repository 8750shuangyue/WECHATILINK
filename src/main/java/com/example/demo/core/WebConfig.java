package com.example.demo.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.demo.config.WebAuthInterceptor;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebAuthInterceptor webAuthInterceptor;
    private final UploadPathProvider uploadPathProvider;
    private final String[] allowedOrigins;

    public WebConfig(WebAuthInterceptor webAuthInterceptor,
                     UploadPathProvider uploadPathProvider,
                     @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
                     String allowedOrigins) {
        this.webAuthInterceptor = webAuthInterceptor;
        this.uploadPathProvider = uploadPathProvider;
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
        // 只在这里映射 /uploads/**，且该路径受 WebAuthInterceptor 保护。
        // 不要再把 uploads 加进 spring.web.resources.static-locations，否则会被 /** 静态资源处理器绕过鉴权。
        String location = uploadPathProvider.root().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
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
