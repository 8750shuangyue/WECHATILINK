package com.example.demo.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileParserServiceTest {

    private final FileParserService service = new FileParserService();

    @Test
    void parsesDocxParagraphsWithPoi6() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("宠物护理指南");
            document.createParagraph().createRun().setText("每日饮水量需要关注");
            document.write(out);
            docx = out.toByteArray();
        }

        String text = service.parseBytes(docx, "care-guide.docx");

        assertTrue(text.contains("宠物护理指南"));
        assertTrue(text.contains("每日饮水量需要关注"));
    }

    @Test
    void rejectsTextAboveCharacterLimit() {
        FileParserService limited = new FileParserService();
        ReflectionTestUtils.setField(limited, "maxExtractedChars", 10);

        assertThrows(FileValidationException.class,
                () -> limited.parseBytes("0123456789ABC".getBytes(StandardCharsets.UTF_8), "notes.txt"));
    }

    @Test
    void rejectsDocxAboveParagraphLimit() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("one");
            document.createParagraph().createRun().setText("two");
            document.createParagraph().createRun().setText("three");
            document.write(out);
            docx = out.toByteArray();
        }

        FileParserService limited = new FileParserService();
        ReflectionTestUtils.setField(limited, "maxDocxParagraphs", 2);

        assertThrows(FileValidationException.class,
                () -> limited.parseBytes(docx, "doc.docx"));
    }

    @Test
    void rejectsPdfAbovePageLimit() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(out);
            pdf = out.toByteArray();
        }

        FileParserService limited = new FileParserService();
        ReflectionTestUtils.setField(limited, "maxPdfPages", 1);

        assertThrows(FileValidationException.class,
                () -> limited.parseBytes(pdf, "doc.pdf"));
    }
}
