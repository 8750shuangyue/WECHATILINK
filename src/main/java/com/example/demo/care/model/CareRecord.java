package com.example.demo.care.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "care_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RecordType recordType;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "JSON")
    private String metadata;

    private Boolean isCompleted;

    private LocalDateTime reminderTime;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (isCompleted == null) {
            isCompleted = false;
        }
    }

    public enum RecordType {
        MEDICATION, VITALS, SYMPTOM, REMINDER, DIAGNOSIS, ADVICE, CARE
    }

    public enum TargetType {
        PET, PLANT
    }
}
