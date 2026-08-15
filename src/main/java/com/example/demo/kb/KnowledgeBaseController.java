package com.example.demo.kb;

import com.example.demo.chat.VectorStoreService;
import com.example.demo.core.FileParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理：上传文档 → 分块向量化入库 → 查看/删除
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private static final int CHUNK_LENGTH = 500;

    private final FileParserService fileParserService;
    private final VectorStoreService vectorStoreService;

    public KnowledgeBaseController(FileParserService fileParserService,
                                   VectorStoreService vectorStoreService) {
        this.fileParserService = fileParserService;
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new HashMap<>();
        try {
            String text = fileParserService.parseFile(file);
            String fileName = file.getOriginalFilename();
            String sourceId = "kb_" + System.currentTimeMillis() + "_" + fileName;
            List<String> chunks = chunk(text);
            for (String c : chunks) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("fileName", fileName);
                vectorStoreService.saveDocument(sourceId, c, meta);
            }
            log.info("[KB] Uploaded {} -> {} chunks ({} chars)", fileName, chunks.size(), text.length());
            r.put("success", true);
            r.put("count", chunks.size());
            r.put("chars", text.length());
        } catch (Exception e) {
            log.error("[KB] Upload failed", e);
            r.put("success", false);
            r.put("error", e.getMessage());
        }
        return ResponseEntity.ok(r);
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", vectorStoreService.listDocuments());
        return ResponseEntity.ok(r);
    }

    @DeleteMapping("/{sourceId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String sourceId) {
        vectorStoreService.clearDocumentVectors(sourceId);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        return ResponseEntity.ok(r);
    }

    private List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        String[] paragraphs = text.split("\\n+");
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (buf.length() + t.length() > CHUNK_LENGTH && buf.length() > 0) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(t).append("\n");
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        return out;
    }
}
