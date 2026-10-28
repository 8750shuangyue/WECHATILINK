package com.example.demo.kb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbWriteAccessGuardTest {

    @Test
    void deniesWhenWritesDisabled() {
        KbWriteAccessGuard guard = new KbWriteAccessGuard(false, "admin");

        assertFalse(guard.canWrite("admin"));
    }

    @Test
    void allowsListedAdminWhenEnabled() {
        KbWriteAccessGuard guard = new KbWriteAccessGuard(true, " admin , operator ");

        assertTrue(guard.canWrite("admin"));
        assertTrue(guard.canWrite("operator"));
        assertFalse(guard.canWrite("someone"));
        assertFalse(guard.canWrite(null));
    }
}
