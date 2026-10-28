package com.example.demo.core;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InlineImageValidatorTest {

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};

    private final InlineImageValidator validator =
            new InlineImageValidator(new FileUploadValidator(), 6 * 1024 * 1024);

    @Test
    void acceptsRawBase64Png() {
        byte[] decoded = validator.decodeAndValidate(Base64.getEncoder().encodeToString(PNG));
        assertArrayEquals(PNG, decoded);
    }

    @Test
    void acceptsDataUrlPng() {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
        assertArrayEquals(PNG, validator.decodeAndValidate(dataUrl));
    }

    @Test
    void rejectsBlank() {
        assertThrows(FileValidationException.class, () -> validator.decodeAndValidate(" "));
    }

    @Test
    void rejectsInvalidBase64() {
        assertThrows(FileValidationException.class, () -> validator.decodeAndValidate("!!!not-base64!!!"));
    }

    @Test
    void rejectsNonImageContent() {
        String base64 = Base64.getEncoder().encodeToString("hello".getBytes());
        assertThrows(FileValidationException.class, () -> validator.decodeAndValidate(base64));
    }

    @Test
    void rejectsOversizedPayloadBeforeDecoding() {
        InlineImageValidator small = new InlineImageValidator(new FileUploadValidator(), 16);
        String base64 = Base64.getEncoder().encodeToString(new byte[64]);
        assertThrows(FileValidationException.class, () -> small.decodeAndValidate(base64));
    }
}
