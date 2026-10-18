package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureRolloutTest {

    @Test
    void emptyCanaryListIncludesEveryone() {
        assertTrue(FeatureRollout.includes("any-user", ""));
        assertTrue(FeatureRollout.includes("any-user", null));
        assertTrue(FeatureRollout.includes(null, " "));
    }

    @Test
    void canaryListMatchesExactUsersOnly() {
        assertTrue(FeatureRollout.includes("eval-user", "eval-user,admin"));
        assertTrue(FeatureRollout.includes(" admin ", "eval-user, admin "));
        assertFalse(FeatureRollout.includes("other-user", "eval-user,admin"));
        assertFalse(FeatureRollout.includes("eval", "eval-user"));
        assertFalse(FeatureRollout.includes(null, "eval-user"));
    }
}
