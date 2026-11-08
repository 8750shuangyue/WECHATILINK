package com.example.demo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LogSanitizerTest {

    @Test
    void describeOnlyExposesLength() {
        assertEquals("len=5", LogSanitizer.describe("hello"));
        assertEquals("len=0", LogSanitizer.describe(""));
        assertEquals("null", LogSanitizer.describe(null));
        assertFalse(LogSanitizer.describe("my private question").contains("private"));
    }

    @Test
    void describeArgsDoesNotLeakValues() {
        String args = "{\"query\":\"猫咪中毒怎么办\"}";
        assertEquals("len=" + args.length(), LogSanitizer.describeArgs(args));
        assertFalse(LogSanitizer.describeArgs(args).contains("中毒"));
    }

    @Test
    void secretStateOnlyReportsWhetherConfigured() {
        assertEquals("unset", LogSanitizer.secretState(null));
        assertEquals("unset", LogSanitizer.secretState("   "));
        assertEquals("set(len=8)", LogSanitizer.secretState("abcd1234"));
    }
}
