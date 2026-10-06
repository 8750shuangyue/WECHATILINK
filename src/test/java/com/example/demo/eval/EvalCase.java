package com.example.demo.eval;

import java.util.ArrayList;
import java.util.List;

public class EvalCase {

    private String id;
    private int version;
    private String path;
    private String category;
    private String question;
    private List<String> expectedFacts = new ArrayList<>();
    private List<String> forbiddenClaims = new ArrayList<>();
    private List<String> expectedSourceIds = new ArrayList<>();
    private List<String> expectedTools = new ArrayList<>();
    private List<String> forbiddenTools = new ArrayList<>();
    private boolean shouldRefuse;
    private List<String> multiTurn = new ArrayList<>();
    private boolean critical;
    private String notes;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<String> getExpectedFacts() {
        return expectedFacts;
    }

    public void setExpectedFacts(List<String> expectedFacts) {
        this.expectedFacts = expectedFacts == null ? new ArrayList<>() : expectedFacts;
    }

    public List<String> getForbiddenClaims() {
        return forbiddenClaims;
    }

    public void setForbiddenClaims(List<String> forbiddenClaims) {
        this.forbiddenClaims = forbiddenClaims == null ? new ArrayList<>() : forbiddenClaims;
    }

    public List<String> getExpectedSourceIds() {
        return expectedSourceIds;
    }

    public void setExpectedSourceIds(List<String> expectedSourceIds) {
        this.expectedSourceIds = expectedSourceIds == null ? new ArrayList<>() : expectedSourceIds;
    }

    public List<String> getExpectedTools() {
        return expectedTools;
    }

    public void setExpectedTools(List<String> expectedTools) {
        this.expectedTools = expectedTools == null ? new ArrayList<>() : expectedTools;
    }

    public List<String> getForbiddenTools() {
        return forbiddenTools;
    }

    public void setForbiddenTools(List<String> forbiddenTools) {
        this.forbiddenTools = forbiddenTools == null ? new ArrayList<>() : forbiddenTools;
    }

    public boolean isShouldRefuse() {
        return shouldRefuse;
    }

    public void setShouldRefuse(boolean shouldRefuse) {
        this.shouldRefuse = shouldRefuse;
    }

    public List<String> getMultiTurn() {
        return multiTurn;
    }

    public void setMultiTurn(List<String> multiTurn) {
        this.multiTurn = multiTurn == null ? new ArrayList<>() : multiTurn;
    }

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}