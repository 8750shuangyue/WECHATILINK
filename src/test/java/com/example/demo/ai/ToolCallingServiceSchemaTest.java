package com.example.demo.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.FileAnalysisTool;
import com.example.demo.agent.tools.ImageAnalysisTool;
import com.example.demo.agent.tools.ImageEditTool;
import com.example.demo.agent.tools.ImageGenerationTool;
import com.example.demo.agent.tools.TtsTool;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.care.service.CareAdvancedService;
import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.NearbyServiceSearchService;
import com.example.demo.care.service.PetCareQueryService;
import com.example.demo.care.service.PetFoodSafetyService;
import com.example.demo.care.service.PlantSafetyQueryService;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.UserSessionService;
import com.example.demo.disease.DiseaseRecognitionService;
import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolCallingServiceSchemaTest {

    @Test
    void triageSchemaAllowsMissingDurationAndAge() {
        SpringAiTools tools = new SpringAiTools(
                mock(WeatherService.class),
                mock(WebSearchTool.class),
                mock(TtsTool.class),
                mock(ImageAnalysisTool.class),
                mock(ImageGenerationTool.class),
                mock(ImageEditTool.class),
                mock(FileAnalysisTool.class),
                mock(CareReminderService.class),
                mock(CareRecordService.class),
                mock(CareAdvancedService.class),
                mock(PetCareQueryService.class),
                mock(PlantSafetyQueryService.class),
                mock(PetFoodSafetyService.class),
                mock(NearbyServiceSearchService.class),
                mock(DiseaseRecognitionService.class),
                mock(UserSessionService.class)
        );
        ToolCallingService service = new ToolCallingService(
                mock(LlmService.class),
                tools,
                mock(WeatherService.class),
                mock(JdbcTemplate.class)
        );

        try {
            JSONArray schemas = service.buildToolsSchema(Set.of("triageSymptoms"));
            assertEquals(1, schemas.size());

            JSONObject function = schemas.getJSONObject(0).getJSONObject("function");
            assertTrue(function.getString("description").contains("不得因持续时间或年龄未知而延迟调用"));

            JSONArray required = function.getJSONObject("parameters").getJSONArray("required");
            assertEquals(Set.of("symptoms"), new HashSet<>(required.toJavaList(String.class)));
        } finally {
            service.shutdown();
        }
    }
}
