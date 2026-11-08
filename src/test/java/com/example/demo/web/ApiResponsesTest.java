package com.example.demo.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponsesTest {

    @Test
    void successPayloadKeepsFieldsAndAddsEnvelope() {
        Map<String, Object> result = ApiResponses.enrich(Map.of("success", true, "content", "hello"));

        assertEquals(200, result.get("code"));
        assertEquals(true, result.get("success"));
        assertEquals("操作成功", result.get("message"));
        assertEquals("hello", result.get("content"));
    }

    @Test
    void errorPayloadAddsCodeAndMessageButKeepsError() {
        Map<String, Object> result = ApiResponses.enrich(Map.of("success", false, "error", "boom"));

        assertEquals(400, result.get("code"));
        assertEquals(false, result.get("success"));
        assertEquals("boom", result.get("message"));
        assertEquals("boom", result.get("error"));
    }
}
