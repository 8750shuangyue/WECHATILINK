package com.example.demo.core;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileUploadValidatorTest {

    private final FileUploadValidator validator = new FileUploadValidator();

    @Test
    void acceptsUtf8Text() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "你好".getBytes(StandardCharsets.UTF_8));

        assertEquals("txt", validator.validateText(file));
    }

    @Test
    void rejectsTextWithNulByte() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", new byte[]{'a', 0, 'b'});

        assertThrows(FileValidationException.class, () -> validator.validateText(file));
    }

    @Test
    void rejectsInvalidUtf8Text() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", new byte[]{(byte) 0xC3, 0x28});

        assertThrows(FileValidationException.class, () -> validator.validateText(file));
    }

    @Test
    void acceptsPngWhenContentMatchesExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", pngHeader());

        assertEquals("png", validator.validateImage(file));
    }

    @Test
    void rejectsImageWhenExtensionDoesNotMatchMagicBytes() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", pngHeader());

        assertThrows(FileValidationException.class, () -> validator.validateImage(file));
    }

    @Test
    void acceptsPdfHeaderWithinFirstKilobyte() {
        byte[] content = new byte[32];
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, content, 4, 8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", content);

        assertEquals("pdf", validator.validateDocument(file));
    }

    @Test
    void acceptsMinimalDocxStructure() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxContent());

        assertEquals("docx", validator.validateDocument(file));
    }

    @Test
    void acceptsWebmAudio() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "voice.webm", "audio/webm",
                new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01});

        assertEquals("webm", validator.validateAudio(file));
    }

    @Test
    void rejectsFakeAudioContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "voice.webm", "audio/webm", "not audio".getBytes(StandardCharsets.UTF_8));

        assertThrows(FileValidationException.class, () -> validator.validateAudio(file));
    }

    private byte[] pngHeader() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }

    private byte[] docxContent() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
