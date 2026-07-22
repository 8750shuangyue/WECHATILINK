package com.example.demo.weather.tool;

import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);

    public static final String TOOL_NAME = "getWeather";
    public static final String TOOL_DESCRIPTION = "查询指定城市的天气信息，包括当前天气、温度、湿度、风速等";

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    public String getToolDefinition() {
        return """
            {
              "name": "%s",
              "description": "%s",
              "parameters": {
                "type": "object",
                "properties": {
                  "city": {
                    "type": "string",
                    "description": "城市名称，如：北京、上海、广州"
                  }
                },
                "required": ["city"]
              }
            }
            """.formatted(TOOL_NAME, TOOL_DESCRIPTION);
    }

    public String execute(String city) {
        logger.info("Executing weather tool for city: {}", city);
        
        try {
            WeatherResponse response = weatherService.getWeatherByCity(city);
            return formatWeatherResult(response);
        } catch (Exception e) {
            logger.error("Weather tool execution failed", e);
            return "查询天气失败: " + e.getMessage();
        }
    }

    private String formatWeatherResult(WeatherResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("城市：").append(response.getCity()).append("\n");
        
        if (response.getCurrent() != null) {
            WeatherResponse.CurrentWeather current = response.getCurrent();
            sb.append("天气：").append(current.getWeather() != null ? current.getWeather() : "未知").append("\n");
            sb.append("温度：").append(current.getTemperature() != null ? current.getTemperature() + "°C" : "未知").append("\n");
            
            if (current.getHumidity() != null) {
                sb.append("湿度：").append(current.getHumidity()).append("%\n");
            }
            if (current.getWindSpeed() != null) {
                sb.append("风速：").append(current.getWindSpeed()).append(" km/h\n");
            }
            if (current.getWindDirection() != null) {
                sb.append("风向：").append(current.getWindDirection()).append("\n");
            }
        }
        
        if (response.getUpdateTime() != null) {
            sb.append("更新时间：").append(response.getUpdateTime()).append("\n");
        }
        
        if (response.getForecast() != null && !response.getForecast().isEmpty()) {
            sb.append("\n未来几天预报：\n");
            for (WeatherResponse.ForecastDay day : response.getForecast()) {
                sb.append("  ").append(day.getDate())
                  .append("：").append(day.getDayWeather())
                  .append("，").append(day.getLowTemp()).append("°C ~ ").append(day.getHighTemp()).append("°C\n");
            }
        }
        
        return sb.toString();
    }

    public static boolean matchesIntent(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        
        String msg = userMessage.toLowerCase();
        return msg.contains("天气") || msg.contains("气温") || msg.contains("温度") 
            || msg.contains("下雨") || msg.contains("晴天") || msg.contains("多云")
            || msg.contains("刮风") || msg.contains("湿度") || msg.contains("预报");
    }

    public static String extractCity(String userMessage) {
        if (userMessage == null) {
            return null;
        }
        
        String[] cityKeywords = {
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "重庆",
            "天津", "苏州", "郑州", "长沙", "东莞", "青岛", "合肥", "佛山", "沈阳", "厦门",
            "哈尔滨", "大连", "宁波", "福州", "无锡", "昆明", "济南", "温州", "南宁", "长春"
        };
        
        for (String city : cityKeywords) {
            if (userMessage.contains(city)) {
                return city;
            }
        }
        
        return null;
    }
}