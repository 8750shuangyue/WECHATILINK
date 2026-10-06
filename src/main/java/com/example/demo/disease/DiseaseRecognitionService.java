package com.example.demo.disease;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.care.model.IdentifyHistory;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import com.example.demo.disease.model.DiseaseResult;
import com.example.demo.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DiseaseRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(DiseaseRecognitionService.class);

    private final VisionService visionService;
    private final IdentifyHistoryRepository identifyHistoryRepository;

    public DiseaseRecognitionService(VisionService visionService,
                                     IdentifyHistoryRepository identifyHistoryRepository) {
        this.visionService = visionService;
        this.identifyHistoryRepository = identifyHistoryRepository;
    }

    private static final String PLANT_PROMPT = "你是一位资深植物病理学专家。请仔细分析这张植物照片，诊断可能存在的病虫害问题。\n\n" +
            "请按照以下JSON格式返回结果（不要包含markdown标记）：\n" +
            "{\n" +
            "  \"diseaseName\": \"病害名称（如：白粉病、黑斑病、锈病、蚜虫侵害等，如果健康则写'健康'）\",\n" +
            "  \"confidence\": \"置信度：HIGH/MEDIUM/LOW\",\n" +
            "  \"symptoms\": \"详细症状描述（病斑颜色、形状、分布位置，叶片状态，茎干情况等）\",\n" +
            "  \"treatmentPlan\": \"治疗方案（分步骤：1.物理处理 2.药剂选择 3.施药方法和频率 4.隔离措施）\",\n" +
            "  \"prevention\": \"预防建议（环境管理、定期检查、增强抵抗力等）\",\n" +
            "  \"urgencyLevel\": \"紧急程度：IMMEDIATE/24H/OBSERVE\"\n" +
            "}\n\n" +
            "常见病害参考：白粉病（白色粉末状霉层）、黑斑病（黑褐色圆形斑点）、锈病（铁锈色孢子堆）、灰霉病（灰色霉层）、蚜虫（黄色小虫聚集）、红蜘蛛（叶片黄白斑点、丝网）、炭疽病（凹陷褐色斑点）。";

    private static final String PET_PROMPT = "你是一位资深宠物皮肤科专家。请仔细分析这张宠物皮肤照片，诊断可能存在的皮肤问题。\n\n" +
            "请按照以下JSON格式返回结果（不要包含markdown标记）：\n" +
            "{\n" +
            "  \"diseaseName\": \"皮肤问题名称（如：猫癣、蠕形螨、过敏性皮炎、湿疹、跳蚤叮咬等，如果健康则写'健康'）\",\n" +
            "  \"confidence\": \"置信度：HIGH/MEDIUM/LOW\",\n" +
            "  \"symptoms\": \"详细症状描述（皮肤颜色、皮疹形态、脱毛情况、红肿程度、分泌物等）\",\n" +
            "  \"treatmentPlan\": \"治疗方案（分步骤：1.清洁消毒 2.外用药选择（人用/兽用） 3.口服药建议 4.是否需要就医）\",\n" +
            "  \"prevention\": \"预防建议（环境消毒、定期驱虫、饮食调整、增强免疫力等）\",\n" +
            "  \"urgencyLevel\": \"紧急程度：IMMEDIATE/24H/OBSERVE\"\n" +
            "}\n\n" +
            "常见宠物皮肤问题参考：猫癣（圆形脱毛斑、皮屑、瘙痒）、蠕形螨（脱毛、红斑、色素沉着）、过敏性皮炎（瘙痒、红斑、抓痕）、湿疹（红肿、渗液）、跳蚤过敏性皮炎（尾根部丘疹）、细菌性皮炎（脓疱、结痂）。\n" +
            "注意：这是AI辅助识别，不能替代兽医诊断，请明确告知用户这一点。";

    public DiseaseResult diagnose(byte[] imageBytes, String type, String userId, Long targetId) throws IOException {
        String prompt = "plant".equalsIgnoreCase(type) ? PLANT_PROMPT : PET_PROMPT;

        logger.info("Disease diagnosis request, type: {}, userId: {}", type, userId);

        String aiResponse = visionService.analyzeImageWithCustomPrompt(imageBytes, prompt);
        logger.info("AI disease diagnosis response received, length: {}", aiResponse != null ? aiResponse.length() : 0);

        DiseaseResult result = parseDiseaseResult(aiResponse);

        IdentifyHistory history = IdentifyHistory.builder()
                .userId(userId)
                .targetId(targetId)
                .identifyType("DISEASE")
                .result(JSON.toJSONString(result))
                .imageUrl(null)
                .metadata(JSON.toJSONString(new JSONObject().fluentPut("type", type)))
                .build();
        identifyHistoryRepository.save(history);
        logger.info("Disease diagnosis history saved for user: {}", userId);

        return result;
    }

    private DiseaseResult parseDiseaseResult(String aiResponse) {
        DiseaseResult result = new DiseaseResult();
        result.setRawAnalysis(aiResponse);

        try {
            JSONObject json = JSON.parseObject(aiResponse);
            result.setDiseaseName(json.getString("diseaseName"));
            result.setConfidence(json.getString("confidence"));
            result.setSymptoms(json.getString("symptoms"));
            result.setTreatmentPlan(json.getString("treatmentPlan"));
            result.setPrevention(json.getString("prevention"));
            result.setUrgencyLevel(json.getString("urgencyLevel"));
        } catch (Exception e) {
            logger.warn("Failed to parse AI response as JSON, using raw analysis. Error: {}", e.getMessage());
        }

        return result;
    }
}
