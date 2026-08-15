package com.example.demo.aicare;

import com.example.demo.chat.entity.PetProfile;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.chat.LlmService;
import com.example.demo.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PetService {

    private static final Logger logger = LoggerFactory.getLogger(PetService.class);

    private final VisionService visionService;
    private final LlmService llmService;
    private final PetProfileRepository petProfileRepository;

    public PetService(VisionService visionService, LlmService llmService, 
                      PetProfileRepository petProfileRepository) {
        this.visionService = visionService;
        this.llmService = llmService;
        this.petProfileRepository = petProfileRepository;
    }

    public Map<String, Object> recognizePet(MultipartFile file, String userId) throws IOException {
        logger.info("Recognizing pet from image, filename: {}, size: {} bytes", 
                file.getOriginalFilename(), file.getSize());

        String imageAnalysis = visionService.analyzeImageWithCustomPrompt(
                file.getBytes(),
                "请识别图片中的宠物种类，并以JSON格式返回：{\"species\": \"宠物品种\", \"description\": \"宠物描述\", \"healthObservation\": \"健康观察\"}"
        );

        Map<String, Object> analysisResult = parseAnalysisResult(imageAnalysis);
        String species = (String) analysisResult.getOrDefault("species", "未知宠物");
        String healthObservation = (String) analysisResult.getOrDefault("healthObservation", "");

        String healthStatus = llmService.chat(
                "根据以下观察：" + healthObservation + "，请评估这只" + species + "的健康状态，并给出护理建议和注意事项。",
                "你是一位专业的兽医，请用通俗易懂的语言提供健康评估和护理建议。"
        );

        String imageUrl = "/uploads/" + file.getOriginalFilename();

        PetProfile profile = new PetProfile(species, species, imageUrl, healthStatus);
        profile.setUserId(userId);
        petProfileRepository.save(profile);

        Map<String, Object> result = new HashMap<>();
        result.put("species", species);
        result.put("healthStatus", healthStatus);
        result.put("id", profile.getId());
        result.put("imageUrl", imageUrl);

        logger.info("Pet recognition completed, species: {}, healthStatus length: {} chars", 
                species, healthStatus.length());

        return result;
    }

    public List<PetProfile> listPets(String userId) {
        return petProfileRepository.findByUserId(userId);
    }

    private Map<String, Object> parseAnalysisResult(String jsonString) {
        try {
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(jsonString);
            Map<String, Object> result = new HashMap<>();
            result.put("species", json.getString("species"));
            result.put("description", json.getString("description"));
            result.put("healthObservation", json.getString("healthObservation"));
            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse vision result, using raw text: {}", jsonString);
            Map<String, Object> result = new HashMap<>();
            result.put("species", jsonString);
            result.put("description", jsonString);
            result.put("healthObservation", "");
            return result;
        }
    }
}
