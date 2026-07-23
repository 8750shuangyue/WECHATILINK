package com.example.wechat.bot.core.service;

import com.example.wechat.bot.common.config.WeatherProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 天气查询服务（对接心知天气 API）。
 * 作为 Function Calling 中的 get_weather 工具的后端实现。
 *
 * 心知天气 API 直接识别中文城市名，无需 adcode 转换。
 * 端点：https://api.seniverse.com/v3/weather/now.json
 * 参数：location（城市名）、key（API Key）、language=zh-Hans、unit=c
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final WeatherProperties properties;

    public WeatherService(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 查询指定城市的实时天气
     *
     * @param city 城市名（中文，如"北京""上海""杭州"）
     * @return JSON 字符串，包含温度、天气、湿度等信息；失败返回错误描述
     */
    public String getWeather(String city) {
        String apiKey = properties.getKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "{\"error\": \"天气 API Key 未配置\"}";
        }

        try {
            String result = requestWeather(city, apiKey);
            if (result != null) {
                return result;
            }

            log.warn("首次天气数据为空，尝试重试...");
            return retryGetWeather(city, apiKey);
        } catch (Exception e) {
            log.error("查询天气失败: city={}", city, e);
            return "{\"error\": \"查询天气失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 执行天气请求
     */
    private String requestWeather(String city, String apiKey) throws Exception {
        String url = properties.getBaseUrl() + "?location=" + city + "&key=" + apiKey + "&language=zh-Hans&unit=c";

        log.info("天气请求URL: {}", url);
        String response = restClient.get()
                .uri(url)
                .header("User-Agent", "WechatBot/1.0")
                .retrieve()
                .body(String.class);
        log.info("天气响应: {}", response != null ? (response.length() > 200 ? response.substring(0, 200) + "..." : response) : "null");

        if (response == null) {
            return "{\"error\": \"天气 API 返回空\"}";
        }

        JsonNode results = JSON_MAPPER.readTree(response).path("results");
        if (results.isMissingNode() || !results.isArray() || results.isEmpty()) {
            return null;
        }

        JsonNode now = results.get(0).path("now");
        JsonNode location = results.get(0).path("location");

        String temperature = now.path("temperature").asText(null);
        String weather = now.path("text").asText(null);
        String humidity = now.path("humidity").asText(null);
        String windDir = now.path("wind_direction").asText(null);
        String windScale = now.path("wind_scale").asText(null);
        String obsTime = results.get(0).path("last_update").asText(null);

        return buildWeatherResult(city, weather, temperature, humidity, windDir, windScale, obsTime);
    }

    /**
     * 重试获取天气数据
     */
   private String retryGetWeather(String city, String apiKey) {
       int maxRetries = 3;
       for (int i = 0; i < maxRetries; i++) {
           try {
               log.info("第 {} 次重试天气查询...", i + 1);
               Thread.sleep(500 * (i + 1));

               String url = properties.getBaseUrl() + "?location=" + city + "&key=" + apiKey + "&language=zh-Hans&unit=c";
               String response = restClient.get()
                       .uri(url)
                       .header("User-Agent", "WechatBot/1.0")
                       .retrieve()
                       .body(String.class);

               if (response != null) {
                   JsonNode results = JSON_MAPPER.readTree(response).path("results");
                   if (!results.isMissingNode() && results.isArray() && !results.isEmpty()) {
                       JsonNode now = results.get(0).path("now");
                       if (!now.isMissingNode() && !now.isNull()) {
                       String temperature = now.path("temperature").asText(null);
                       String weather = now.path("text").asText(null);
                       String humidity = now.path("humidity").asText(null);
                       String windDir = now.path("wind_direction").asText(null);
                       String windScale = now.path("wind_scale").asText(null);
                       String obsTime = results.get(0).path("last_update").asText(null);
                       
                       String result = buildWeatherResult(city, weather, temperature, humidity, windDir, windScale, obsTime);
                       log.info("重试成功");
                       return result;
                       }                   }
               }
           } catch (Exception e) {
               log.error("重试失败", e);
           }
       }
     return "{\"error\": \"多次重试后仍未获取到天气数据\"}";
  }

    /**
     * 统一构建天气结果 JSON
     */
    private static String buildWeatherResult(String city, String weather, String temperature,
                                             String humidity, String windDir, String windScale,
                                             String obsTime) throws Exception {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("city", city);
        if (weather != null) m.put("weather", weather);
        if (temperature != null) m.put("temperature", temperature + "\u00b0C");
        if (humidity != null && !humidity.equals("null")) m.put("humidity", humidity + "%");
        if (windDir != null && !windDir.equals("null")) m.put("wind_direction", windDir);
        if (windScale != null && !windScale.equals("null")) m.put("wind_power", windScale);
        if (obsTime != null) m.put("report_time", obsTime);
        return JSON_MAPPER.writeValueAsString(m);
    }
}
