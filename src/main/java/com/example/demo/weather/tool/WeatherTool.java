package com.example.demo.weather.tool;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.BaseTool;
import com.example.demo.agent.tools.ToolDefinition;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool extends BaseTool {

    public static final String TOOL_NAME = "getWeather";
    public static final String TOOL_DESCRIPTION = "查询指定城市的天气信息，包括当前天气、温度、湿度、风速等";

    private final WeatherService weatherService;
    private final ToolDefinition toolDefinition;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("city", "string", "城市名称，如：北京、上海、广州")
                .required("city")
                .build();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public ToolDefinition getDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolResult<String> execute(JSONObject params) {
        String city = params.getString("city");
        
        if (city == null || city.trim().isEmpty()) {
            return ToolResult.failure("请提供城市名称，例如：北京、上海、广州");
        }
        
        logger.info("Executing weather tool for city: {}", city);
        
        try {
            WeatherResponse response = weatherService.getWeatherByCity(city);
            String formattedResult = formatWeatherResult(response);
            return ToolResult.success(formattedResult);
        } catch (Exception e) {
            logger.error("Weather tool execution failed", e);
            return ToolResult.failure("查询天气失败，可能是网络问题或城市名称不正确");
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