package com.example.demo.care.service;

import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.care.repository.CareRecordRepository;
import com.example.demo.care.repository.CareTargetRepository;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import com.example.demo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CareAdvancedServiceTest {

    @Test
    void treatsTrafficTraumaWithRapidBreathingAsEmergency() {
        CareAdvancedService service = new CareAdvancedService(
                mock(JdbcTemplate.class),
                mock(WebSearchTool.class),
                mock(CareRecordRepository.class),
                mock(CareTargetRepository.class),
                mock(IdentifyHistoryRepository.class),
                mock(WeatherService.class)
        );

        String triage = service.triage("猫被车撞后还能走路但呼吸很急", "未知", "未知");

        assertTrue(triage.contains("立即就医"));
    }
}
