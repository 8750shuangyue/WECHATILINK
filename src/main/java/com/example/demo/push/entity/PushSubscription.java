package com.example.demo.push.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "push_subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String endpoint;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String p256dh;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String auth;

    @Column
    private String userAgent;

    /**
     * 生产表里这一列是 NOT NULL 且没有默认值。实体曾经缺这个字段，
     * 导致 insert 不带 enabled，保存订阅时报 "Field 'enabled' doesn't have a default value"。
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
