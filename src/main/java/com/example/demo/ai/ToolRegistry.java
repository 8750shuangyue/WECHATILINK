package com.example.demo.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.weather.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表：扫描 SpringAiTools / WeatherService 上的 @Tool 方法，
 * 保证名称唯一，并负责生成模型可用的 function schema。
 * 不参与工具执行与对话循环。
 */
@Slf4j
final class ToolRegistry {

    private final Map<String, ToolInfo> tools = new LinkedHashMap<>();

    ToolRegistry(SpringAiTools springAiTools, WeatherService weatherService) {
        registerTools(springAiTools);
        registerTools(weatherService);
        log.info("ToolRegistry initialized, registered {} tools: {}", tools.size(), tools.keySet());
    }

    private void registerTools(Object toolObject) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }
            String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
            ToolInfo previous = tools.putIfAbsent(toolName, new ToolInfo(toolObject, method, annotation));
            if (previous != null) {
                throw new IllegalStateException("Duplicate tool name '" + toolName + "' in "
                        + toolObject.getClass().getName() + "; tool names must be unique");
            }
            log.info("Registered tool: {}", toolName);
        }
    }

    Set<String> names() {
        return new HashSet<>(tools.keySet());
    }

    boolean contains(String name) {
        return tools.containsKey(name);
    }

    int size() {
        return tools.size();
    }

    ToolInfo get(String name) {
        return tools.get(name);
    }

    JSONArray buildSchema() {
        return buildSchema(null);
    }

    JSONArray buildSchema(Set<String> allowedToolNames) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, ToolInfo> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            if (allowedToolNames != null && !allowedToolNames.isEmpty()
                    && !allowedToolNames.contains(toolName)) {
                continue;
            }
            ToolInfo info = entry.getValue();
            JSONObject tool = new JSONObject();
            tool.put("type", "function");

            JSONObject function = new JSONObject();
            function.put("name", toolName);
            function.put("description", info.annotation.description().isEmpty()
                    ? toolName : info.annotation.description());
            function.put("parameters", buildParametersSchema(info.method));
            tool.put("function", function);
            result.add(tool);
        }
        log.info("Built tools schema with {} tools", result.size());
        return result;
    }

    private JSONObject buildParametersSchema(Method method) {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (Parameter param : method.getParameters()) {
            String paramName = param.getName();
            JSONObject paramSchema = new JSONObject();
            paramSchema.put("type", getJsonType(param.getType()));

            ToolParam annotation = param.getAnnotation(ToolParam.class);
            if (annotation != null && !annotation.description().isEmpty()) {
                paramSchema.put("description", annotation.description());
            } else {
                paramSchema.put("description", "Parameter: " + paramName);
            }
            properties.put(paramName, paramSchema);

            if (annotation == null || annotation.required()) {
                required.add(paramName);
            }
        }
        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }
        return parameters;
    }

    private String getJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || List.class.isAssignableFrom(type)) return "array";
        return "object";
    }
}
