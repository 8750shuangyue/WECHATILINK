package com.example.demo.chat.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "plant_profile")
public class PlantProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "species", length = 100)
    private String species;

    @Column(name = "ai_image_url", length = 500)
    private String aiImageUrl;

    @Column(name = "care_tips", columnDefinition = "TEXT")
    private String careTips;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "user_id", length = 100)
    private String userId;

    public PlantProfile() {
    }

    public PlantProfile(String name, String species, String aiImageUrl, String careTips) {
        this.name = name;
        this.species = species;
        this.aiImageUrl = aiImageUrl;
        this.careTips = careTips;
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

    public String getCareTips() {
        return careTips;
    }

    public void setCareTips(String careTips) {
        this.careTips = careTips;
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
