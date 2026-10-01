package com.example.demo.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        Integer version,
        String path,
        String category,
        String question,
        List<String> expectedFacts,
        List<String> forbiddenClaims,
        List<String> expectedSourceIds,
        List<String> expectedTools,
        List<String> forbiddenTools,
        List<String> allowedTools,
        Boolean shouldRefuse,
        List<String> multiTurn,
        Boolean critical,
        String notes
) {
    public EvalCase {
        id = trim(id);
        version = version == null ? 1 : version;
        path = trim(path).isEmpty() ? "stream" : trim(path);
        category = trim(category);
        question = trim(question);
        expectedFacts = safeList(expectedFacts);
        forbiddenClaims = safeList(forbiddenClaims);
        expectedSourceIds = safeList(expectedSourceIds);
        expectedTools = safeList(expectedTools);
        forbiddenTools = safeList(forbiddenTools);
        allowedTools = safeList(allowedTools);
        shouldRefuse = Boolean.TRUE.equals(shouldRefuse);
        multiTurn = safeList(multiTurn);
        critical = Boolean.TRUE.equals(critical);
        notes = trim(notes);
    }

    public List<String> turns() {
        List<String> turns = new ArrayList<>();
        if (!question.isBlank()) {
            turns.add(question);
        }
        turns.addAll(multiTurn);
        return turns;
    }

    public void validate() {
        if (id.isBlank()) {
            throw new IllegalArgumentException("Eval case id is required");
        }
        if (!"stream".equals(path) && !"tools".equals(path)) {
            throw new IllegalArgumentException("Unsupported path for " + id + ": " + path);
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("Category is required for " + id);
        }
        if (turns().isEmpty()) {
            throw new IllegalArgumentException("Question or multiTurn is required for " + id);
        }
        if ("tools".equals(path) && expectedTools.isEmpty() && forbiddenTools.isEmpty()) {
            throw new IllegalArgumentException("Tool case must declare expectedTools or forbiddenTools: " + id);
        }
    }

    private static List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
