package com.example.demo.core;

import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class FileParserService {

    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);

    private static final String LIMIT_MARKER = "__ILINK_TEXT_LIMIT__";

    private static final List<String> TEXT_EXTENSIONS = Arrays.asList("txt", "md", "json", "csv", "log");
    private static final List<String> PDF_EXTENSIONS = Arrays.asList("pdf");
    private static final List<String> DOCX_EXTENSIONS = Arrays.asList("docx");

    @Value("${app.upload.max-pdf-pages:200}")
    private int maxPdfPages = 200;

    @Value("${app.upload.max-extracted-chars:500000}")
    private int maxExtractedChars = 500_000;

    @Value("${app.upload.max-docx-paragraphs:20000}")
    private int maxDocxParagraphs = 20_000;

    @Value("${app.upload.parse-timeout-seconds:45}")
    private int parseTimeoutSeconds = 45;

    @Value("${app.upload.parse-max-concurrent:2}")
    private int parseMaxConcurrent = 2;

    @Value("${app.upload.temp-dir:./tmp}")
    private String tempDir = "./tmp";

    @Value("${app.guard.acquire-timeout-ms:200}")
    private long acquireTimeoutMs = 200;

    private volatile ExecutorService parseExecutor;
    private volatile Semaphore parseSlots;

    public String parseFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String extension = getFileExtension(filename).toLowerCase();
        logger.info("Parsing file: {}, extension: {}", filename, extension);

        if (!supports(filename)) {
            throw new IllegalArgumentException("不支持的文件类型: " + extension);
        }
        return parseBytesGuarded(file.getBytes(), extension);
    }

    public String parseBytes(byte[] content, String filename) throws IOException {
        String extension = getFileExtension(filename).toLowerCase();
        logger.info("Parsing bytes: {}, extension: {}", filename, extension);
        return parseBytesGuarded(content, extension);
    }

    public boolean supports(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        String extension = getFileExtension(filename).toLowerCase();
        return TEXT_EXTENSIONS.contains(extension)
                || PDF_EXTENSIONS.contains(extension)
                || DOCX_EXTENSIONS.contains(extension);
    }

    /**
     * 解析统一走有界线程池 + 信号量：单个超大/畸形文档不会长期占满 Tomcat 请求线程。
     */
    private String parseBytesGuarded(byte[] content, String extension) throws IOException {
        if (content == null || content.length == 0) {
            throw new FileValidationException("文件内容为空");
        }
        ensureGuard();

        Future<String> future;
        try {
            future = parseExecutor.submit(() -> {
                boolean acquired = false;
                try {
                    acquired = parseSlots.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!acquired) {
                        throw new FileValidationException("文档解析繁忙，请稍后再试");
                    }
                    return parseInternal(content, extension);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("文档解析被中断", e);
                } finally {
                    if (acquired) {
                        parseSlots.release();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            throw new FileValidationException("文档解析繁忙，请稍后再试", e);
        }

        try {
            return future.get(Math.max(1, parseTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("Document parsing timed out after {}s", parseTimeoutSeconds);
            throw new FileValidationException("文档解析超时，请拆分后再试");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("文档解析被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FileValidationException validation) {
                throw validation;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("文档解析失败", cause);
        }
    }

    private String parseInternal(byte[] content, String extension) throws IOException {
        if (TEXT_EXTENSIONS.contains(extension)) {
            return parseText(content);
        }
        if (PDF_EXTENSIONS.contains(extension)) {
            return parsePdf(content);
        }
        if (DOCX_EXTENSIONS.contains(extension)) {
            return parseDocx(content);
        }
        throw new FileValidationException("不支持的文件类型: " + extension);
    }

    private String parseText(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.length() > maxExtractedChars) {
            throw new FileValidationException("文本内容超过上限（" + maxExtractedChars + " 字符）");
        }
        return text;
    }

    private String parsePdf(byte[] content) throws IOException {
        RandomAccessStreamCache.StreamCacheCreateFunction cache;
        try {
            Path dir = Path.of(tempDir).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(dir);
            cache = MemoryUsageSetting.setupTempFileOnly().setTempDir(dir.toFile()).streamCache;
        } catch (Exception e) {
            // 临时目录不可用时退回内存缓存，仍受页数/字符数上限约束
            logger.warn("PDF temp dir unavailable, falling back to main memory: {}", e.getMessage());
            cache = MemoryUsageSetting.setupMainMemoryOnly().streamCache;
        }

        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(content);
             PDDocument document = Loader.loadPDF(source, cache)) {
            int pages = document.getNumberOfPages();
            if (pages > maxPdfPages) {
                throw new FileValidationException("PDF 页数超过上限（" + maxPdfPages + " 页）");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(pages, maxPdfPages));
            LimitedStringWriter writer = new LimitedStringWriter(maxExtractedChars);
            try {
                stripper.writeText(document, writer);
            } catch (IOException e) {
                if (LIMIT_MARKER.equals(e.getMessage())) {
                    throw new FileValidationException("PDF 提取文本超过上限（" + maxExtractedChars + " 字符）");
                }
                throw e;
            }
            logger.info("PDF parsed, pages: {}, content length: {}", pages, writer.length());
            return writer.toString();
        }
    }

    private String parseDocx(byte[] content) throws IOException {
        try (InputStream is = new java.io.ByteArrayInputStream(content);
             XWPFDocument document = new XWPFDocument(is)) {
            StringBuilder text = new StringBuilder();
            int paragraphs = 0;
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                paragraphs++;
                if (paragraphs > maxDocxParagraphs) {
                    throw new FileValidationException("DOCX 段落数超过上限（" + maxDocxParagraphs + "）");
                }
                String paragraphText = paragraph.getText();
                if (paragraphText == null || paragraphText.isEmpty()) {
                    continue;
                }
                if (text.length() + paragraphText.length() > maxExtractedChars) {
                    throw new FileValidationException("DOCX 提取文本超过上限（" + maxExtractedChars + " 字符）");
                }
                text.append(paragraphText).append('\n');
            }
            logger.info("DOCX parsed, paragraphs: {}, content length: {}", paragraphs, text.length());
            return text.toString();
        }
    }

    private void ensureGuard() {
        if (parseExecutor != null) {
            return;
        }
        synchronized (this) {
            if (parseExecutor != null) {
                return;
            }
            int concurrency = Math.max(1, parseMaxConcurrent);
            parseSlots = new Semaphore(concurrency);
            parseExecutor = new ThreadPoolExecutor(
                    concurrency,
                    concurrency,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(concurrency),
                    runnable -> {
                        Thread thread = new Thread(runnable, "doc-parse");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService executor = parseExecutor;
        if (executor != null) {
            executor.shutdownNow();
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

    /**
     * 超过字符上限时抛 IOException，由 PDFTextStripper 原样透出，
     * 调用方用 message 区分“超限”与真正的 IO 错误。
     */
    private static final class LimitedStringWriter extends Writer {

        private final StringBuilder buffer = new StringBuilder();
        private final int limit;

        private LimitedStringWriter(int limit) {
            this.limit = Math.max(1, limit);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (buffer.length() + len > limit) {
                throw new IOException(LIMIT_MARKER);
            }
            buffer.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }

        private int length() {
            return buffer.length();
        }

        @Override
        public String toString() {
            return buffer.toString();
        }
    }
}
