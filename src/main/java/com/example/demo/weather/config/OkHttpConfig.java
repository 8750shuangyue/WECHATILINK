package com.example.demo.weather.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    private final WeatherApiProperties weatherApiProperties;

    public OkHttpConfig(WeatherApiProperties weatherApiProperties) {
        this.weatherApiProperties = weatherApiProperties;
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(weatherApiProperties.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(weatherApiProperties.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(weatherApiProperties.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build();
    }
}