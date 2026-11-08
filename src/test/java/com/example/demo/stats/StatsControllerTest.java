package com.example.demo.stats;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatsControllerTest {

    @Test
    void overviewUsesUnifiedResultEnvelope() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcTemplate sqlite = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        when(sqlite.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new StatsController(jdbc, sqlite))
                .build();

        mvc.perform(get("/api/stats/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.users").value(1))
                .andExpect(jsonPath("$.data.kbDocuments").value(2));
    }
}
