package com.example.demo.aicare;

import com.example.demo.chat.entity.PlantProfile;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
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
public class PlantService {

    private static final Logger logger = LoggerFactory.getLogger(PlantService.class);

    private final VisionService visionService;
    private final LlmService llmService;
    private final PlantProfileRepository plantProfileRepository;

    public PlantService(VisionService visionService, LlmService llmService, 
                        PlantProfileRepository plantProfileRepository) {
        this.visionService = visionService;
        this.llmService = llmService;
        this.plantProfileRepository = plantProfileRepository;
    }

    public Map<String, Object> recognizePlant(MultipartFile file) throws IOException {
        logger.info("Recognizing plant from image, filename: {}, size: {} bytes", 
                file.getOriginalFilename(), file.getSize());

        String imageAnalysis = visionService.analyzeImageWithCustomPrompt(
                file.getBytes(),
                "请识别图片中的植物种类，并以JSON格式返回：{\"species\": \"植物品种\", \"description\": \"植物描述\"}"
        );

        Map<String, Object> analysisResult = parseAnalysisResult(imageAnalysis);
        String species = (String) analysisResult.getOrDefault("species", "未知植物");

        String careTips = llmService.chat(
                "请为" + species + "生成详细的养护建议，包括光照、水分、温度、施肥、注意事项等方面。",
                "你是一位专业的园艺师，请用通俗易懂的语言提供养护建议。"
        );

        String imageUrl = "/uploads/" + file.getOriginalFilename();

        PlantProfile profile = new PlantProfile(species, species, imageUrl, careTips);
        plantProfileRepository.save(profile);

        Map<String, Object> result = new HashMap<>();
        result.put("species", species);
        result.put("careTips", careTips);
        result.put("id", profile.getId());
        result.put("imageUrl", imageUrl);

        logger.info("Plant recognition completed, species: {}, careTips length: {} chars", 
                species, careTips.length());

        return result;
    }

    public List<PlantProfile> listPlants() {
        return plantProfileRepository.findAll();
    }

    private Map<String, Object> parseAnalysisResult(String jsonString) {
        try {
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(jsonString);
            Map<String, Object> result = new HashMap<>();
            result.put("species", json.getString("species"));
            result.put("description", json.getString("description"));
            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse vision result, using raw text: {}", jsonString);
            Map<String, Object> result = new HashMap<>();
            result.put("species", jsonString);
            result.put("description", jsonString);
            return result;
        }
    }
}
