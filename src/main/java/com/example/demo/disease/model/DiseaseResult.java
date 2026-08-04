package com.example.demo.disease.model;

import lombok.Data;

@Data
public class DiseaseResult {
    private String diseaseName;
    private String confidence;
    private String symptoms;
    private String treatmentPlan;
    private String prevention;
    private String urgencyLevel;
    private String rawAnalysis;
}
