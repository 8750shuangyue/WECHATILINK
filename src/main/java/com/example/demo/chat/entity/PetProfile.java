package com.example.demo.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pet_profile")
public class PetProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "species", length = 100)
    private String species;

    @Column(name = "ai_image_url", length = 500)
    private String aiImageUrl;

    @Column(name = "health_status", length = 50)
    private String healthStatus;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "user_id", length = 100)
    private String userId;

    public PetProfile() {
    }

    public PetProfile(String name, String species, String aiImageUrl, String healthStatus) {
        this.name = name;
        this.species = species;
        this.aiImageUrl = aiImageUrl;
        this.healthStatus = healthStatus;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getAiImageUrl() {
        return aiImageUrl;
    }

    public void setAiImageUrl(String aiImageUrl) {
        this.aiImageUrl = aiImageUrl;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
