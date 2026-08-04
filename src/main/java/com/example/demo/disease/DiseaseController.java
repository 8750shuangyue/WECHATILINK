package com.example.demo.disease;

import com.example.demo.aicare.Result;
import com.example.demo.care.model.IdentifyHistory;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import com.example.demo.disease.model.DiseaseResult;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/disease")
public class DiseaseController {

    private static final Logger logger = LoggerFactory.getLogger(DiseaseController.class);

    private final DiseaseRecognitionService diseaseRecognitionService;
    private final IdentifyHistoryRepository identifyHistoryRepository;

    public DiseaseController(DiseaseRecognitionService diseaseRecognitionService,
                             IdentifyHistoryRepository identifyHistoryRepository) {
        this.diseaseRecognitionService = diseaseRecognitionService;
        this.identifyHistoryRepository = identifyHistoryRepository;
    }

    @PostMapping("/diagnose")
    public Result<DiseaseResult> diagnose(@RequestParam("file") MultipartFile file,
                                          @RequestParam("type") String type,
                                          HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        logger.info("Disease diagnose request, type: {}, filename: {}, user: {}",
                type, file.getOriginalFilename(), userName);

        try {
            byte[] imageBytes = file.getBytes();
            DiseaseResult result = diseaseRecognitionService.diagnose(imageBytes, type, userName, null);
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Disease diagnosis failed", e);
            return Result.error("病害诊断失败：" + e.getMessage());
        }
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        logger.info("Disease history request, user: {}", userName);

        List<IdentifyHistory> histories = identifyHistoryRepository
                .findByUserIdAndIdentifyTypeOrderByCreatedAtDesc(userName, "DISEASE");

        List<Map<String, Object>> result = new ArrayList<>();
        int limit = Math.min(histories.size(), 20);
        for (int i = 0; i < limit; i++) {
            IdentifyHistory h = histories.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("id", h.getId());
            item.put("result", h.getResult());
            item.put("createdAt", h.getCreatedAt() != null ? h.getCreatedAt().toString() : null);
            item.put("metadata", h.getMetadata());
            result.add(item);
        }

        return Result.success(result);
    }
}
