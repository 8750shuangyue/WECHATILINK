package com.example.demo.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据观测：平台概览、工具调用排行、近 7 天对话趋势。
 * 接口统一走 WebAuthInterceptor 鉴权（需登录）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final Logger log = LoggerFactory.getLogger(StatsController.class);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate sqliteJdbc;

    public StatsController(JdbcTemplate jdbc,
                           @Qualifier("sqliteJdbcTemplate") JdbcTemplate sqliteJdbc) {
        this.jdbc = jdbc;
        this.sqliteJdbc = sqliteJdbc;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("users", count(jdbc, "SELECT COUNT(*) FROM users"));
        r.put("conversations", count(jdbc, "SELECT COUNT(*) FROM conversations"));
        r.put("messages", count(jdbc, "SELECT COUNT(*) FROM messages"));
        r.put("toolCalls", count(jdbc, "SELECT COUNT(*) FROM tool_call_logs"));
        r.put("careTargets", count(jdbc, "SELECT COUNT(*) FROM care_targets"));
        r.put("careRecords", count(jdbc, "SELECT COUNT(*) FROM care_records"));
        r.put("pendingReminders", count(jdbc, "SELECT COUNT(*) FROM care_reminder WHERE status='PENDING'"));
        r.put("products", count(jdbc, "SELECT COUNT(*) FROM products"));
        r.put("posts", count(jdbc, "SELECT COUNT(*) FROM community_posts"));
        r.put("kbDocuments", count(sqliteJdbc, "SELECT COUNT(DISTINCT source_id) FROM vector_store"));
        return r;
    }

    @GetMapping("/tool-ranking")
    public List<Map<String, Object>> toolRanking() {
        try {
            return jdbc.queryForList("""
                    SELECT tool_name AS toolName,
                           COUNT(*) AS total,
                           SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCount,
                           ROUND(AVG(duration_ms)) AS avgMs
                    FROM tool_call_logs
                    GROUP BY tool_name
                    ORDER BY total DESC
                    LIMIT 10
                    """);
        } catch (Exception e) {
            log.warn("[Stats] tool ranking failed: {}", e.getMessage());
            return List.of();
        }
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            LocalDate start = LocalDate.now().minusDays(6);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT DATE(timestamp) AS day, COUNT(*) AS cnt
                    FROM messages
                    WHERE timestamp >= ?
                    GROUP BY day
                    ORDER BY day
                    """, start.atStartOfDay());

            Map<String, Long> byDay = new HashMap<>();
            for (Map<String, Object> row : rows) {
                byDay.put(String.valueOf(row.get("day")), ((Number) row.get("cnt")).longValue());
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            for (int i = 0; i < 7; i++) {
                LocalDate d = start.plusDays(i);
                String key = d.format(fmt);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", key);
                item.put("count", byDay.getOrDefault(key, 0L));
                result.add(item);
            }
        } catch (Exception e) {
            log.warn("[Stats] trend failed: {}", e.getMessage());
        }
        return result;
    }

    private long count(JdbcTemplate t, String sql) {
        try {
            Long c = t.queryForObject(sql, Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            log.warn("[Stats] count failed: {}", e.getMessage());
            return 0;
        }
    }
}
