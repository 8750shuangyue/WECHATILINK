package com.example.demo.care;

import com.example.demo.aicare.Result;
import com.example.demo.audit.OperationAuditService;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.core.FileUploadValidator;
import com.example.demo.core.InlineImageValidator;
import com.example.demo.vision.VisionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CareControllerInlineImageTest {

    private CareController controller(VisionService visionService) {
        return new CareController(
                visionService,
                mock(LlmService.class),
                mock(PlantProfileRepository.class),
                mock(PetProfileRepository.class),
                mock(LegacyCareRecordRepository.class),
                new InlineImageValidator(new FileUploadValidator(), 6 * 1024 * 1024),
                mock(OperationAuditService.class));
    }

    @Test
    void rejectsUnknownType() {
        Result<Map<String, Object>> result = controller(mock(VisionService.class))
                .identify(Map.of("type", "OTHER", "image", "x"), new MockHttpServletRequest());

        assertEquals(400, result.getCode());
    }

    @Test
    void rejectsInvalidBase64() {
        Result<Map<String, Object>> result = controller(mock(VisionService.class))
                .identify(Map.of("type", "PLANT", "image", "!!!not-base64!!!"),
                        new MockHttpServletRequest());

        assertEquals(400, result.getCode());
    }

    @Test
    void rejectsNonImageContent() {
        String base64 = Base64.getEncoder().encodeToString("hello".getBytes());
        Result<Map<String, Object>> result = controller(mock(VisionService.class))
                .identify(Map.of("type", "PET", "image", base64), new MockHttpServletRequest());

        assertEquals(400, result.getCode());
    }

    @Test
    void acceptsValidPng() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        VisionService vision = mock(VisionService.class);
        when(vision.analyzeImageWithCustomPrompt(eq(png), eq("image/png"), anyString()))
                .thenReturn("识别结果");

        Result<Map<String, Object>> result = controller(vision)
                .identify(Map.of("type", "PLANT",
                        "image", Base64.getEncoder().encodeToString(png)), new MockHttpServletRequest());

        assertEquals(200, result.getCode());
        assertEquals("识别结果", result.getData().get("result"));
    }
}
