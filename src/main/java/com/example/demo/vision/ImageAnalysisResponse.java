package com.example.demo.vision;

import lombok.Data;

import java.util.List;

@Data
public class ImageAnalysisResponse {
    private String title;
    private String description;
    private List<String> objects;
    private String scene;
    private String emotion;
    private List<String> tags;
    private String text;
}