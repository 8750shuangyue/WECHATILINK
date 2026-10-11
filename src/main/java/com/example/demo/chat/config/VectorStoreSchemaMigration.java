package com.example.demo.chat.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VectorStoreSchemaMigration {

    private static final Logger logger = LoggerFactory.getLogger(VectorStoreSchemaMigration.class);

    private final JdbcTemplate sqliteJdbcTemplate;

    public VectorStoreSchemaMigration(
            @Qualifier("sqliteJdbcTemplate") JdbcTemplate sqliteJdbcTemplate) {
        this.sqliteJdbcTemplate = sqliteJdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        sqliteJdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_vector_store_document_id "
                        + "ON vector_store (document_id)");
        logger.info("[Schema] Ensured unique index uk_vector_store_document_id");
    }
}
