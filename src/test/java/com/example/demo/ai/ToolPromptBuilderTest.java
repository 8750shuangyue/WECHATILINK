package com.example.demo.ai;

import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPromptBuilderTest {

    private static Set<String> liveToolNames() {
        Set<String> names = new HashSet<>();
        for (Class<?> type : List.of(SpringAiTools.class, WeatherService.class)) {
            for (Method method : type.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    names.add(annotation.name().isEmpty() ? method.getName() : annotation.name());
                }
            }
        }
        return names;
    }

    @Test
    void everyLiveToolBelongsToAGroup() {
        assertEquals(liveToolNames(), ToolPromptBuilder.groupedToolNames(),
                "新增 @Tool 时必须同步加入 ToolPromptBuilder 的分组，否则提示词会漏掉该工具");
    }

    @Test
    void promptOnlyListsAllowedGroups() {
        String prompt = ToolPromptBuilder.build(Set.of("getWeather"), liveToolNames());

        assertTrue(prompt.contains("实时天气"));
        assertTrue(prompt.contains("getWeather"));
        assertFalse(prompt.contains("图像多模态"));
        assertFalse(prompt.contains("analyzeImage"));
    }

    @Test
    void promptListsAllGroupsWhenAllowListIsEmpty() {
        String prompt = ToolPromptBuilder.build(Set.of(), liveToolNames());

        assertTrue(prompt.contains("实时天气"));
        assertTrue(prompt.contains("图像多模态"));
        assertTrue(prompt.contains("护理/健康管理"));
    }
}
