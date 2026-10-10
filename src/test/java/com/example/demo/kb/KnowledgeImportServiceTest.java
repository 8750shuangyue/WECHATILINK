package com.example.demo.kb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeImportServiceTest {

    private final KnowledgeImportService service = new KnowledgeImportService();

    @Test
    void generatesStableSourceIdFromCanonicalFileName() {
        KnowledgeImportService.ImportPlan first =
                service.plan(null, "guides\\月季 黑斑病.md", "第一段\n第二段");
        KnowledgeImportService.ImportPlan second =
                service.plan(null, "guides/月季 黑斑病.md", "第一段\n第二段");
        KnowledgeImportService.ImportPlan different =
                service.plan(null, "pet-safety.md", "第一段\n第二段");

        assertEquals(first.sourceId(), second.sourceId());
        assertNotEquals(first.sourceId(), different.sourceId());
        assertTrue(first.sourceId().startsWith("kb_"));
        assertEquals(35, first.sourceId().length());
        assertEquals("月季 黑斑病.md", first.fileName());
    }

    @Test
    void acceptsExplicitTraceableSourceId() {
        KnowledgeImportService.ImportPlan plan =
                service.plan("guide:plant:blackspot:v1", "blackspot.md", "月季黑斑病处理");

        assertEquals("guide:plant:blackspot:v1", plan.sourceId());
        assertEquals(1, plan.chunks().size());
        assertEquals("月季黑斑病处理", plan.chunks().get(0));
    }

    @Test
    void rejectsInvalidSourceId() {
        assertThrows(KnowledgeImportException.class,
                () -> service.plan("guide/plant", "blackspot.md", "月季黑斑病处理"));
        assertThrows(KnowledgeImportException.class,
                () -> service.plan("guide plant", "blackspot.md", "月季黑斑病处理"));
    }

    @Test
    void rejectsBlankParsedText() {
        assertThrows(KnowledgeImportException.class,
                () -> service.plan("guide-1", "empty.txt", " \n \t "));
    }
}
