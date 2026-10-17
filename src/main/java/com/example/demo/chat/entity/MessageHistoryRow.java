package com.example.demo.chat.entity;

import java.time.LocalDateTime;

/**
 * active + archive 合并历史查询的投影。
 */
public interface MessageHistoryRow {
    Long getId();

    String getRole();

    String getContent();

    LocalDateTime getTs();

    String getSourcesJson();
}
