package com.example.demo.care.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "identify_history", indexes = {
    @Index(name = "idx_user_type_time", columnList = "user_id, identify_type, created_at DESC"),
    @Index(name = "idx_target_id", columnList = "target_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentifyHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    private Long targetId;

    @Column(nullable = false, length = 20)
    private String identifyType;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(length = 500)
    private String imageUrl;

    @Column(columnDefinition = "JSON")
    private String metadata;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}