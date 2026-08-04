package com.example.demo.weather.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.example.demo.weather.config.WeatherApiProperties;
import com.example.demo.weather.dto.WeatherApiResponse;
import com.example.demo.weather.exception.WeatherApiException;
import com.example.demo.weather.model.WeatherResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WeatherService {

    private final OkHttpClient okHttpClient;
    private final WeatherApiProperties weatherApiProperties;

    public WeatherService(OkHttpClient okHttpClient, WeatherApiProperties weatherApiProperties) {
        this.okHttpClient = okHttpClient;
        this.weatherApiProperties = weatherApiProperties;
    }

    @Tool(name = "queryWeather", description = "查询指定城市的实时天气信息")
    public WeatherResponse getWeatherByCity(@ToolParam(description = "城市名称，如：北京、上海、杭州") String city) {
        validateCity(city);
        
        log.info("查询城市天气: {}", city);
        
        String url = buildWeatherUrl(city);
        log.debug("请求 URL: {}", maskApiKey(url));
        
        try {
            String responseBody = executeRequest(url);
            log.debug("天气 API 响应体: {}", responseBody);
            
            WeatherApiResponse apiResponse = parseResponse(responseBody);
            
            if (apiResponse.getStatusCode() != null && !apiResponse.getStatusCode().equals("200")) {
                log.error("天气 API 错误: statusCode={}, status={}", apiResponse.getStatusCode(), apiResponse.getStatus());
                throw new WeatherApiException("天气API返回错误: " + apiResponse.getStatus());
            }
            
            return convertToWeatherResponse(apiResponse);
        } catch (WeatherApiException e) {
            log.error("获取天气数据失败，城市: {}, 原因: {}", city, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("获取天气数据异常，城市: {}", city, e);
            throw new WeatherApiException("获取天气数据失败: " + e.getMessage(), e);
        }
    }

    private void validateCity(String city) {
        if (city == null || city.trim().isEmpty()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
    }

    private String buildWeatherUrl(String city) {
        try {
            return String.format("%s?key=%s&location=%s&language=zh-Hans&unit=c&start=0&days=5", 
                    weatherApiProperties.getBaseUrl(), 
                    weatherApiProperties.getApiKey(),
                    java.net.URLEncoder.encode(city, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            return String.format("%s?key=%s&location=%s&language=zh-Hans&unit=c&start=0&days=5", 
                    weatherApiProperties.getBaseUrl(), 
                    weatherApiProperties.getApiKey(),
                    city);
        }
    }

    private String maskApiKey(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("key=[^&]+", "key=***");
    }

    private String executeRequest(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Weather/1.0.0")
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            int statusCode = response.code();
            log.info("HTTP 响应状态码: {}", statusCode);

            if (!response.isSuccessful()) {
                String errorBody = Optional.ofNullable(response.body())
                        .map(body -> {
                            try {
                                return body.string();
                            } catch (IOException e) {
                                return "";
                            }
                        })
                        .orElse("");
                
                if (statusCode == 401) {
                    throw new WeatherApiException("API Key 无效或过期", 401);
                } else if (statusCode == 404) {
                    throw new WeatherApiException("城市未找到", 404);
                } else if (statusCode == 429) {
                    throw new WeatherApiException("请求过于频繁，请稍后重试", 429);
                } else {
                    throw new WeatherApiException("HTTP 请求失败，状态码: " + statusCode + ", 响应: " + errorBody, statusCode);
                }
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new WeatherApiException("响应体为空");
            }

            return body.string();
        }
    }

    private WeatherApiResponse parseResponse(String responseBody) throws IOException {
        log.debug("响应体长度: {} 字符", responseBody.length());
        
        WeatherApiResponse response;
        try {
            response = JSON.parseObject(responseBody, WeatherApiResponse.class);
        } catch (JSONException e) {
            throw new IOException("JSON解析失败: " + e.getMessage(), e);
        }
        
        if (response.getStatusCode() != null && !response.getStatusCode().equals("200")) {
            log.warn("天气 API 返回错误: statusCode={}, status={}", response.getStatusCode(), response.getStatus());
            return response;
        }
        
        if (response.getResults() == null || response.getResults().isEmpty()) {
            throw new WeatherApiException("未获取到天气数据，请检查城市名称是否正确");
        }
        
        return response;
    }

    private WeatherResponse convertToWeatherResponse(WeatherApiResponse apiResponse) {
        if (apiResponse.getResults() == null || apiResponse.getResults().isEmpty()) {
            throw new WeatherApiException("天气数据为空，请检查城市名称或API配置");
        }

        WeatherApiResponse.Result result = apiResponse.getResults().get(0);
        String locationName = result.getLocation();
        
        if (locationName == null || locationName.isEmpty()) {
            throw new WeatherApiException("位置信息为空");
        }

        WeatherApiResponse.Daily todayWeather = Optional.ofNullable(result.getDaily())
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .orElse(null);

        WeatherResponse.CurrentWeather currentWeather = WeatherResponse.CurrentWeather.builder()
                .temperature(parseDouble(todayWeather, WeatherApiResponse.Daily::getHigh))
                .weather(Optional.ofNullable(todayWeather).map(WeatherApiResponse.Daily::getTextDay).orElse(null))
                .humidity(parseDouble(todayWeather, WeatherApiResponse.Daily::getHumidity))
                .windSpeed(parseDouble(todayWeather, WeatherApiResponse.Daily::getWindSpeed))
                .windDirection(Optional.ofNullable(todayWeather).map(WeatherApiResponse.Daily::getWindDirection).orElse(null))
                .pressure(parseDouble(todayWeather, WeatherApiResponse.Daily::getPressure))
                .visibility(parseDouble(todayWeather, WeatherApiResponse.Daily::getVisibility))
                .build();

        List<WeatherResponse.ForecastDay> forecastDays = Optional.ofNullable(result.getDaily())
                .orElse(Collections.emptyList())
                .stream()
                .map(this::convertForecast)
                .collect(Collectors.toList());

        return WeatherResponse.builder()
                .city(locationName)
                .updateTime(result.getLastUpdate())
                .current(currentWeather)
                .forecast(forecastDays)
                .build();
    }

    private Double parseDouble(WeatherApiResponse.Daily daily, java.util.function.Function<WeatherApiResponse.Daily, String> getter) {
        if (daily == null) {
            return null;
        }
        try {
            String value = getter.apply(daily);
            return value != null ? Double.parseDouble(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WeatherResponse.ForecastDay convertForecast(WeatherApiResponse.Daily daily) {
        return WeatherResponse.ForecastDay.builder()
                .date(daily.getDate())
                .highTemp(parseDouble(daily, WeatherApiResponse.Daily::getHigh))
                .lowTemp(parseDouble(daily, WeatherApiResponse.Daily::getLow))
                .dayWeather(daily.getTextDay())
                .nightWeather(daily.getTextNight())
                .dayWindDirection(daily.getWindDirection())
                .nightWindDirection(daily.getWindDirection())
                .build();
    }
}