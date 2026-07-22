package com.example.demo.weather.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

@Data
public class WeatherApiResponse {

    private List<Result> results;

    @Data
    public static class Result {

        private Location location;

        private List<Daily> daily;

        @JSONField(name = "last_update")
        private String lastUpdate;
    }

    @Data
    public static class Location {

        private String id;

        private String name;

        private String country;

        private String path;

        private String timezone;

        @JSONField(name = "timezone_offset")
        private String timezoneOffset;
    }

    @Data
    public static class Daily {

        private String date;

        @JSONField(name = "text_day")
        private String textDay;

        @JSONField(name = "text_night")
        private String textNight;

        private String high;

        private String low;

        @JSONField(name = "wind_direction")
        private String windDirection;

        @JSONField(name = "wind_direction_degree")
        private String windDirectionDegree;

        @JSONField(name = "wind_speed")
        private String windSpeed;

        @JSONField(name = "wind_scale")
        private String windScale;

        private String humidity;

        private String precip;

        private String pressure;

        private String visibility;

        private String clouds;

        @JSONField(name = "uv_index")
        private String uvIndex;

        @JSONField(name = "uv_index_level")
        private String uvIndexLevel;

        private String quality;

        private String pm25;

        private String pm10;

        private String so2;

        private String no2;

        private String co;

        private String o3;
    }
}