package com.example.demo.ai;

import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具口径契约：运行时注册的是 {@code @Tool} 方法，名称必须唯一，
 * 数量必须与 README / 落地文档里的「24 个工具」一致。
 * 新增或删除工具时，这个测试会失败，提醒同步更新文档。
 */
class ToolRegistryContractTest {

    private static final int DOCUMENTED_TOOL_COUNT = 24;

    @Test
    void liveToolNamesAreUniqueAndMatchDocumentedCount() {
        Set<String> names = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (Class<?> type : List.of(SpringAiTools.class, WeatherService.class)) {
            for (Method method : type.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                if (!names.add(name)) {
                    duplicates.add(name);
                }
            }
        }

        assertTrue(duplicates.isEmpty(), "重复的工具名: " + duplicates);
        assertEquals(DOCUMENTED_TOOL_COUNT, names.size(),
                "运行时工具数与文档不一致；新增/删除工具时请同步更新 README 与落地文档");
    }
}
