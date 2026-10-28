package com.example.demo.kb;

import com.example.demo.audit.OperationAuditService;
import com.example.demo.chat.VectorStoreService;
import com.example.demo.core.FileParserService;
import com.example.demo.core.FileUploadValidator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerAccessTest {

    private MockMvc mvc(boolean writesEnabled, String admins) {
        KnowledgeBaseController controller = new KnowledgeBaseController(
                mock(FileParserService.class),
                mock(FileUploadValidator.class),
                mock(KnowledgeImportService.class),
                mock(VectorStoreService.class),
                new KbWriteAccessGuard(writesEnabled, admins),
                mock(OperationAuditService.class),
                1);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void deniesNormalUserWhenWritesDisabled() throws Exception {
        mvc(false, "admin").perform(delete("/api/kb/some-source")
                        .requestAttr("userName", "normal"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deniesUserNotInAllowlist() throws Exception {
        mvc(true, "admin").perform(delete("/api/kb/some-source")
                        .requestAttr("userName", "normal"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsListedAdmin() throws Exception {
        mvc(true, "admin").perform(delete("/api/kb/some-source")
                        .requestAttr("userName", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
