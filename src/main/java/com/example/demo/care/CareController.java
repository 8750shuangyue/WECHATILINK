package com.example.demo.care;

import com.example.demo.aicare.Result;
import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.entity.CareRecord;
import com.example.demo.chat.entity.PlantProfile;
import com.example.demo.chat.entity.PetProfile;
import com.example.demo.chat.repository.mysql.LegacyCareRecordRepository;
import com.example.demo.chat.repository.mysql.PlantProfileRepository;
import com.example.demo.chat.repository.mysql.PetProfileRepository;
import com.example.demo.chat.LlmService;
import com.example.demo.vision.VisionService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/care")
public class CareController {

    private static final Logger logger = LoggerFactory.getLogger(CareController.class);

    private final VisionService visionService;
    private final LlmService llmService;
    private final PlantProfileRepository plantProfileRepository;
    private final PetProfileRepository petProfileRepository;
    private final LegacyCareRecordRepository careRecordRepository;

    public CareController(VisionService visionService, LlmService llmService,
                          PlantProfileRepository plantProfileRepository,
                          PetProfileRepository petProfileRepository,
                          LegacyCareRecordRepository careRecordRepository) {
        this.visionService = visionService;
        this.llmService = llmService;
        this.plantProfileRepository = plantProfileRepository;
        this.petProfileRepository = petProfileRepository;
        this.careRecordRepository = careRecordRepository;
    }

    @PostMapping("/identify")
    public Result<Map<String, Object>> identify(@RequestBody Map<String, String> params) {
        String type = params.get("type");
        String imageBase64 = params.get("image");

        logger.info("Care identify request, type: {}", type);

        try {
            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
            
            String prompt = type.equals("PLANT") 
                ? "请识别图片中的植物种类，并以详细的文本描述，包括品种名称、外观特征、生长状态等信息。"
                : "请识别图片中的宠物种类，并以详细的文本描述，包括品种名称、外观特征、健康状态观察等信息。";

            String analysis = visionService.analyzeImageWithCustomPrompt(imageBytes, prompt);

            Map<String, Object> result = new HashMap<>();
            result.put("result", analysis);
            
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Care identify failed", e);
            return Result.error("识别失败：" + e.getMessage());
        }
    }

    @GetMapping("/targets/{type}")
    public Result<List<Object>> getTargets(@PathVariable String type, HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        List<Object> targets = new ArrayList<>();
        if ("PLANT".equalsIgnoreCase(type)) {
            for (PlantProfile plant : plantProfileRepository.findByUserId(userName)) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", plant.getId());
                map.put("name", plant.getName());
                map.put("species", plant.getSpecies());
                map.put("createTime", plant.getCreateTime());
                targets.add(map);
            }
        } else if ("PET".equalsIgnoreCase(type)) {
            for (PetProfile pet : petProfileRepository.findByUserId(userName)) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", pet.getId());
                map.put("name", pet.getName());
                map.put("species", pet.getSpecies());
                map.put("createTime", pet.getCreateTime());
                targets.add(map);
            }
        }

        return Result.success(targets);
    }

    @PostMapping("/targets/{type}")
    public Result<String> createTarget(@PathVariable String type, @RequestBody Map<String, Object> params,
                                       HttpSession session) {
        String userName = (String) session.getAttribute("user");
        String name = (String) params.get("name");
        String species = (String) params.get("species");

        if ("PLANT".equalsIgnoreCase(type)) {
            PlantProfile plant = new PlantProfile(name, species, null, null);
            plant.setUserId(userName);
            plantProfileRepository.save(plant);
        } else if ("PET".equalsIgnoreCase(type)) {
            PetProfile pet = new PetProfile(name, species, null, null);
            pet.setUserId(userName);
            petProfileRepository.save(pet);
        }

        return Result.success("保存成功");
    }

    @DeleteMapping("/targets/{type}/{id}")
    public Result<String> deleteTarget(@PathVariable String type, @PathVariable Long id, HttpSession session) {
        String userName = (String) session.getAttribute("user");
        boolean owned = false;
        if ("PLANT".equalsIgnoreCase(type)) {
            owned = plantProfileRepository.findByIdAndUserId(id, userName).isPresent();
        } else if ("PET".equalsIgnoreCase(type)) {
            owned = petProfileRepository.findByIdAndUserId(id, userName).isPresent();
        }
        if (!owned) {
            return Result.error("无权删除该档案");
        }
        if ("PLANT".equalsIgnoreCase(type)) {
            plantProfileRepository.deleteById(id);
            careRecordRepository.deleteByTargetTypeAndTargetId("PLANT", id);
        } else if ("PET".equalsIgnoreCase(type)) {
            petProfileRepository.deleteById(id);
            careRecordRepository.deleteByTargetTypeAndTargetId("PET", id);
        }

        return Result.success("删除成功");
    }

    @GetMapping("/records/{type}/{targetId}")
    public Result<List<Map<String, Object>>> getRecords(@PathVariable String type, @PathVariable Long targetId,
                                                        HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (!ownsTarget(type, targetId, userName)) {
            return Result.error("无权访问该档案");
        }
        List<CareRecord> records = careRecordRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(type, targetId);
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (CareRecord record : records) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", record.getId());
            map.put("recordType", record.getRecordType());
            map.put("content", record.getContent());
            map.put("createdAt", record.getCreatedAt());
            result.add(map);
        }

        return Result.success(result);
    }

    @PostMapping("/records")
    public Result<String> createRecord(@RequestBody Map<String, Object> params, HttpSession session) {
        String targetType = (String) params.get("targetType");
        Long targetId = ((Number) params.get("targetId")).longValue();
        String recordType = (String) params.get("recordType");
        String content = (String) params.get("content");

        String userName = (String) session.getAttribute("user");
        if (!ownsTarget(targetType, targetId, userName)) {
            return Result.error("无权操作该档案");
        }
        CareRecord record = new CareRecord(targetType, targetId, recordType, content);
        careRecordRepository.save(record);

        return Result.success("记录保存成功");
    }

    private boolean ownsTarget(String type, Long targetId, String userName) {
        if (targetId == null || userName == null) {
            return false;
        }
        if ("PLANT".equalsIgnoreCase(type)) {
            return plantProfileRepository.findByIdAndUserId(targetId, userName).isPresent();
        }
        if ("PET".equalsIgnoreCase(type)) {
            return petProfileRepository.findByIdAndUserId(targetId, userName).isPresent();
        }
        return false;
    }

    @PostMapping("/qa")
    public Result<Map<String, Object>> qa(@RequestBody Map<String, Object> params, HttpSession session) {
        String question = (String) params.get("question");
        String targetType = (String) params.get("targetType");
        Long targetId = params.get("targetId") != null ? ((Number) params.get("targetId")).longValue() : null;

        String userName = (String) session.getAttribute("user");
        if (targetType != null && targetId != null && !ownsTarget(targetType, targetId, userName)) {
            return Result.error("无权访问该档案");
        }

        String conversationId = (String) session.getAttribute("conversationId");
        if (conversationId == null) {
            conversationId = "web_" + System.currentTimeMillis() + "_" + System.nanoTime();
            session.setAttribute("conversationId", conversationId);
        }

        logger.info("Care QA request: {}, targetType: {}, targetId: {}, conversationId: {}", question, targetType, targetId, conversationId);

        try {
            StringBuilder context = new StringBuilder();
            String targetName = "";
            String species = "";

            if (targetType != null && targetId != null) {
                if ("PLANT".equalsIgnoreCase(targetType)) {
                    Optional<PlantProfile> plantOpt = plantProfileRepository.findById(targetId);
                    if (plantOpt.isPresent()) {
                        PlantProfile plant = plantOpt.get();
                        targetName = plant.getName();
                        species = plant.getSpecies();
                        context.append("植物档案：").append(plant.getName())
                               .append("，品种：").append(plant.getSpecies())
                               .append("\n");
                    }
                } else if ("PET".equalsIgnoreCase(targetType)) {
                    Optional<PetProfile> petOpt = petProfileRepository.findById(targetId);
                    if (petOpt.isPresent()) {
                        PetProfile pet = petOpt.get();
                        targetName = pet.getName();
                        species = pet.getSpecies();
                        context.append("宠物档案：").append(pet.getName())
                               .append("，品种：").append(pet.getSpecies())
                               .append("\n");
                    }
                }

                List<CareRecord> records = careRecordRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId);
                if (!records.isEmpty()) {
                    context.append("护理记录：\n");
                    for (CareRecord record : records) {
                        context.append("- ").append(record.getRecordType())
                               .append("：").append(record.getContent())
                               .append("（").append(record.getCreatedAt()).append("）\n");
                    }
                }
            }

            String systemPrompt = "你是一位专业的植物和宠物护理专家，请提供详细、科学的护理建议。";
            if (context.length() > 0) {
                systemPrompt = "以下是用户的" + ("PLANT".equalsIgnoreCase(targetType) ? "植物" : "宠物") + "档案和护理记录，请基于这些信息回答问题：\n\n" + 
                               context.toString() + "\n\n" + systemPrompt;
            }

            String reply = llmService.chatWithMemory(conversationId, question, systemPrompt);
            
            Map<String, Object> result = new HashMap<>();
            result.put("reply", reply);
            result.put("targetName", targetName);
            result.put("species", species);
            
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Care QA failed", e);
            return Result.error("问答失败：" + e.getMessage());
        }
    }

    @PostMapping("/qa/summary")
    public Result<Map<String, Object>> qaSummary(@RequestBody Map<String, Object> params) {
        String reply = (String) params.get("reply");
        String targetType = (String) params.get("targetType");
        String targetName = (String) params.get("targetName");

        logger.info("Care QA summary request, targetType: {}, targetName: {}", targetType, targetName);

        try {
            String prompt = "请将以下护理建议压缩成3-5句话，科学地总结护理记录要点和下一步护理建议：\n\n" + reply;
            
            String summary = llmService.chat(prompt, "你是一位专业的" + ("PLANT".equalsIgnoreCase(targetType) ? "植物" : "宠物") + "护理专家，请用简洁、科学的语言总结护理建议。");
            
            Map<String, Object> result = new HashMap<>();
            result.put("summary", summary);
            
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Care QA summary failed", e);
            return Result.error("生成摘要失败：" + e.getMessage());
        }
    }
}
