package com.example.demo.core;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

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
}
