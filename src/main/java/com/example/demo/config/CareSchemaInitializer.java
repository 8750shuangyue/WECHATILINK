package com.example.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 启动时自动执行 db/care_schema.sql 里的建表语句（如 care_reminder）。
 * 这些表只有原生 SQL、没有 JPA 实体，ddl-auto=update 不会创建，必须由本初始化器保证表结构存在。
 */
@Component
public class CareSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(CareSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public CareSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            ClassPathResource resource = new ClassPathResource("db/care_schema.sql");
            if (!resource.exists()) {
                log.warn("[Schema] db/care_schema.sql not found, skip schema initialization");
                return;
            }

            String sql;
            try (InputStream in = resource.getInputStream()) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String[] statements = sql.split(";");
            int executed = 0;
            for (String raw : statements) {
                String stmt = stripComments(raw).trim();
                if (stmt.isEmpty()) {
                    continue;
                }
                try {
                    jdbc.execute(stmt);
                    executed++;
                    log.info("[Schema] Executed: {}", firstLine(stmt));
                } catch (Exception e) {
                    // 表已存在或语法差异（如旧版 MySQL 不支持 DESC 索引）时不阻断启动
                    log.warn("[Schema] Statement skipped ({}): {}", e.getMessage(), firstLine(stmt));
                }
            }
            log.info("[Schema] Schema initialization completed, {} statement(s) executed", executed);
        } catch (Exception e) {
            log.error("[Schema] Schema initialization failed", e);
        }
    }

    private String stripComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }

    private String firstLine(String sql) {
        int idx = sql.indexOf('\n');
        return idx > 0 ? sql.substring(0, idx).trim() : sql;
    }
}
