package com.example.demo.eval;

import com.example.demo.chat.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "ai.eval.enabled", matches = "true")
class KbImportRunnerTest {

    private static final int CHUNK_LENGTH = 500;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Test
    void importKnowledgeBase() throws Exception {
        Path docsDir = Paths.get("eval/kb-docs");
        if (!Files.isDirectory(docsDir)) {
            throw new IllegalStateException("知识文档目录不存在: " + docsDir.toAbsolutePath());
        }
        boolean dryRun = Boolean.parseBoolean(System.getProperty("ai.kb.dry-run", "false"));
        boolean clean = Boolean.parseBoolean(System.getProperty("ai.kb.clean", "false"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(docsDir)) {
            files = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        System.out.println("[KB-IMPORT] 共发现 " + files.size() + " 个知识文档, dryRun=" + dryRun
                + ", clean=" + clean);
        if (clean && !dryRun) {
            List<Map<String, Object>> existing = vectorStoreService.listDocuments();
            for (Map<String, Object> doc : existing) {
                String sid = String.valueOf(doc.get("sourceId"));
                vectorStoreService.clearDocumentVectors(sid);
                System.out.println("[KB-IMPORT] 清理旧文档 sourceId=" + sid);
            }
        }
        int totalChunks = 0;
        for (Path f : files) {
            String sourceId = kbDocSourceId(f.getFileName().toString());
            String text = Files.readString(f, StandardCharsets.UTF_8);
            List<String> chunks = chunk(text);
            if (!dryRun) {
                long replaced = vectorStoreService.replaceDocument(sourceId, chunks,
                        Map.of("fileName", f.getFileName().toString(), "import", "eval"));
                System.out.println("[KB-IMPORT] " + f.getFileName() + " -> sourceId=" + sourceId
                        + " chunks=" + chunks.size() + " chars=" + text.length() + " replaced=" + replaced);
            } else {
                System.out.println("[KB-DRY] " + f.getFileName() + " -> sourceId=" + sourceId
                        + " chunks=" + chunks.size() + " chars=" + text.length());
            }
            totalChunks += chunks.size();
        }
        System.out.println("[KB-IMPORT] 完成，共 " + totalChunks + " 个分块。"
                + "当前向量总数: " + vectorStoreService.countVectors());
    }

    private String kbDocSourceId(String fileName) {
        String base = fileName.replace(".md", "");
        return base.startsWith("kb") ? base : "kb_" + base;
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