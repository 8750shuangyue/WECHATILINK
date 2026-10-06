package com.example.demo.weather.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherResponse {

    private String city;

    private String country;

    private String updateTime;

    private CurrentWeather current;

    private List<ForecastDay> forecast;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentWeather {

        private Double temperature;

        private String weather;

        private String weatherCode;

        private Double humidity;

        private Double windSpeed;

        private String windDirection;

        private Double pressure;

        private Double visibility;

        private Double feelsLike;

        private String sunrise;

        private String sunset;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForecastDay {

        private String date;

        private String week;

        private Double highTemp;

        private Double lowTemp;

        private String dayWeather;

        private String nightWeather;

        private String dayWindDirection;

        private String nightWindDirection;
    }
}