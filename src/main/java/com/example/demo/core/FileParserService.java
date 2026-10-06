package com.example.demo.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Service
public class FileParserService {

    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);

    private static final List<String> TEXT_EXTENSIONS = Arrays.asList("txt", "md", "json", "csv", "log");
    private static final List<String> PDF_EXTENSIONS = Arrays.asList("pdf");
    private static final List<String> DOCX_EXTENSIONS = Arrays.asList("docx");

    public String parseFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String extension = getFileExtension(filename).toLowerCase();
        logger.info("Parsing file: {}, extension: {}", filename, extension);

        if (TEXT_EXTENSIONS.contains(extension)) {
            return parseTextFile(file);
        } else if (PDF_EXTENSIONS.contains(extension)) {
            return parsePdfFile(file);
        } else if (DOCX_EXTENSIONS.contains(extension)) {
            return parseDocxFile(file);
        } else {
            throw new IllegalArgumentException("不支持的文件类型: " + extension);
        }
    }

    public String parseBytes(byte[] content, String filename) throws IOException {
        String extension = getFileExtension(filename).toLowerCase();
        logger.info("Parsing bytes: {}, extension: {}", filename, extension);

        if (TEXT_EXTENSIONS.contains(extension)) {
            return new String(content, StandardCharsets.UTF_8);
        } else if (PDF_EXTENSIONS.contains(extension)) {
            try (PDDocument document = Loader.loadPDF(content)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                logger.info("PDF parsed from bytes, content length: {}", text.length());
                return text;
            }
        } else if (DOCX_EXTENSIONS.contains(extension)) {
            try (InputStream is = new java.io.ByteArrayInputStream(content);
                 XWPFDocument document = new XWPFDocument(is)) {
                StringBuilder text = new StringBuilder();
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    String paragraphText = paragraph.getText();
                    if (paragraphText != null && !paragraphText.isEmpty()) {
                        text.append(paragraphText).append("\n");
                    }
                }
                logger.info("DOCX parsed from bytes, content length: {}", text.length());
                return text.toString();
            }
        } else {
            throw new IllegalArgumentException("不支持的文件类型: " + extension);
        }
    }

    public boolean supports(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        String extension = getFileExtension(filename).toLowerCase();
        return TEXT_EXTENSIONS.contains(extension) || 
               PDF_EXTENSIONS.contains(extension) || 
               DOCX_EXTENSIONS.contains(extension);
    }

    private String parseTextFile(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private String parsePdfFile(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            logger.info("PDF parsed, content length: {}", text.length());
            return text;
        }
    }

    private String parseDocxFile(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             XWPFDocument document = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paragraphText = paragraph.getText();
                if (paragraphText != null && !paragraphText.isEmpty()) {
                    text.append(paragraphText).append("\n");
                }
            }
            logger.info("DOCX parsed, content length: {}", text.length());
            return text.toString();
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    public List<String> getSupportedExtensions() {
        return List.of("txt", "md", "json", "csv", "log", "pdf", "docx");
    }
}