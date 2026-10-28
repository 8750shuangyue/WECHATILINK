package com.example.demo.kb;

import com.example.demo.audit.AuditAction;
import com.example.demo.audit.OperationAuditService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.core.FileParserService;
import com.example.demo.core.FileUploadValidator;
import com.example.demo.core.FileValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 知识库管理：上传文档 → 分块向量化入库 → 查看/删除。
 * 写操作受白名单与并发限制保护。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final FileParserService fileParserService;
    private final FileUploadValidator fileUploadValidator;
    private final KnowledgeImportService knowledgeImportService;
    private final VectorStoreService vectorStoreService;
    private final KbWriteAccessGuard kbWriteAccessGuard;
    private final OperationAuditService auditService;
    private final Semaphore importSlots;

    public KnowledgeBaseController(FileParserService fileParserService,
                                   FileUploadValidator fileUploadValidator,
                                   KnowledgeImportService knowledgeImportService,
                                   VectorStoreService vectorStoreService,
                                   KbWriteAccessGuard kbWriteAccessGuard,
                                   OperationAuditService auditService,
                                   @Value("${app.kb.max-concurrent:1}") int maxConcurrent) {
        this.fileParserService = fileParserService;
        this.fileUploadValidator = fileUploadValidator;
        this.knowledgeImportService = knowledgeImportService;
        this.vectorStoreService = vectorStoreService;
        this.kbWriteAccessGuard = kbWriteAccessGuard;
        this.auditService = auditService;
        this.importSlots = new Semaphore(Math.max(1, maxConcurrent));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceId", required = false) String sourceId,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            HttpServletRequest request) {
        Map<String, Object> r = new HashMap<>();
        String userName = (String) request.getAttribute("userName");
        if (!kbWriteAccessGuard.canWrite(userName)) {
            auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.DENIED,
                    "knowledge", sourceId, "writes disabled or user not in allowlist");
            r.put("success", false);
            r.put("error", "无权修改知识库");
            return ResponseEntity.status(403).body(r);
        }

        if (!acquireImportSlot()) {
            auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.FAILURE,
                    "knowledge", sourceId, "import busy");
            r.put("success", false);
            r.put("error", "知识库导入繁忙，请稍后再试");
            return ResponseEntity.status(429).body(r);
        }

        try {
            fileUploadValidator.validateDocument(file);
            String text = fileParserService.parseFile(file);
            KnowledgeImportService.ImportPlan plan =
                    knowledgeImportService.plan(sourceId, file.getOriginalFilename(), text);

            r.put("success", true);
            r.put("dryRun", dryRun);
            r.put("sourceId", plan.sourceId());
            r.put("fileName", plan.fileName());
            r.put("title", plan.title());
            r.put("count", plan.chunks().size());
            r.put("chars", plan.charCount());

            if (dryRun) {
                auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.SUCCESS,
                        "knowledge", plan.sourceId(), "dryRun chunks=" + plan.chunks().size());
                return ResponseEntity.ok(r);
            }

            long previousCount = vectorStoreService.countVectorsBySource(plan.sourceId());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", "document");
            metadata.put("fileName", plan.fileName());
            metadata.put("importMode", "replace");
            if (plan.title() != null && !plan.title().isBlank()) {
                metadata.put("title", plan.title());
            }
            int count = vectorStoreService.replaceDocument(
                    plan.sourceId(), plan.chunks(), metadata);

            log.info("[KB] Imported {} -> sourceId={}, chunks={}, chars={}, replacedPrevious={}",
                    plan.fileName(), plan.sourceId(), count, plan.charCount(), previousCount);
            auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.SUCCESS,
                    "knowledge", plan.sourceId(),
                    "fileName=" + plan.fileName() + ", chunks=" + count + ", chars=" + plan.charCount());
            r.put("replacedPreviousCount", previousCount);
        } catch (FileValidationException | KnowledgeImportException e) {
            log.warn("[KB] Upload rejected: {}", e.getMessage());
            auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.FAILURE,
                    "knowledge", sourceId, e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(r);
        } catch (Exception e) {
            log.error("[KB] Upload failed", e);
            auditService.record(request, AuditAction.KB_UPLOAD, AuditAction.FAILURE,
                    "knowledge", sourceId, e.getClass().getSimpleName());
            r.put("success", false);
            r.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(r);
        } finally {
            importSlots.release();
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
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String sourceId,
                                                      HttpServletRequest request) {
        Map<String, Object> r = new HashMap<>();
        String userName = (String) request.getAttribute("userName");
        if (!kbWriteAccessGuard.canWrite(userName)) {
            auditService.record(request, AuditAction.KB_DELETE, AuditAction.DENIED,
                    "knowledge", sourceId, "writes disabled or user not in allowlist");
            r.put("success", false);
            r.put("error", "无权修改知识库");
            return ResponseEntity.status(403).body(r);
        }

        vectorStoreService.clearDocumentVectors(sourceId);
        auditService.record(request, AuditAction.KB_DELETE, AuditAction.SUCCESS,
                "knowledge", sourceId, "cleared");
        r.put("success", true);
        return ResponseEntity.ok(r);
    }

    private boolean acquireImportSlot() {
        try {
            return importSlots.tryAcquire(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
