package com.example.demo.kb;

import com.example.demo.chat.VectorStoreService;
import com.example.demo.core.FileParserService;
import com.example.demo.core.FileUploadValidator;
import com.example.demo.core.FileValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库管理：上传文档 → 分块向量化入库 → 查看/删除
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    private final FileParserService fileParserService;
    private final FileUploadValidator fileUploadValidator;
    private final KnowledgeImportService knowledgeImportService;
    private final VectorStoreService vectorStoreService;

    public KnowledgeBaseController(FileParserService fileParserService,
                                   FileUploadValidator fileUploadValidator,
                                   KnowledgeImportService knowledgeImportService,
                                   VectorStoreService vectorStoreService) {
        this.fileParserService = fileParserService;
        this.fileUploadValidator = fileUploadValidator;
        this.knowledgeImportService = knowledgeImportService;
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        Map<String, Object> r = new HashMap<>();
        try {
            fileUploadValidator.validateDocument(file);
            String text = fileParserService.parseFile(file);
            KnowledgeImportService.ImportPlan plan =
                    knowledgeImportService.plan(sourceId, file.getOriginalFilename(), text);

            r.put("success", true);
            r.put("dryRun", dryRun);
            r.put("sourceId", plan.sourceId());
            r.put("fileName", plan.fileName());
            r.put("count", plan.chunks().size());
            r.put("chars", plan.charCount());

            if (dryRun) {
                return ResponseEntity.ok(r);
            }

            long previousCount = vectorStoreService.countVectorsBySource(plan.sourceId());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", "document");
            metadata.put("fileName", plan.fileName());
            metadata.put("importMode", "replace");
            int count = vectorStoreService.replaceDocument(
                    plan.sourceId(), plan.chunks(), metadata);

            log.info("[KB] Imported {} -> sourceId={}, chunks={}, chars={}, replacedPrevious={}",
                    plan.fileName(), plan.sourceId(), count, plan.charCount(), previousCount);
            r.put("replacedPreviousCount", previousCount);
        } catch (FileValidationException | KnowledgeImportException e) {
            log.warn("[KB] Upload rejected: {}", e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(r);
        } catch (Exception e) {
            log.error("[KB] Upload failed", e);
            r.put("success", false);
            r.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(r);
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

}
