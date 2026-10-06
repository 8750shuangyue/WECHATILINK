package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.*;
import com.example.demo.care.service.*;
import com.example.demo.chat.UserSessionService;
import com.example.demo.disease.DiseaseRecognitionService;
import com.example.demo.disease.model.DiseaseResult;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Slf4j
@Service
public class SpringAiTools {

    private final WeatherService weatherService;
    private final WebSearchTool webSearchTool;
    private final TtsTool ttsTool;
    private final ImageAnalysisTool imageAnalysisTool;
    private final ImageGenerationTool imageGenerationTool;
    private final ImageEditTool imageEditTool;
    private final FileAnalysisTool fileAnalysisTool;
    private final CareReminderService careReminderService;
    private final CareRecordService careRecordService;
    private final CareAdvancedService careAdvancedService;
    private final PetCareQueryService petCareQueryService;
    private final PlantSafetyQueryService plantSafetyQueryService;
    private final PetFoodSafetyService petFoodSafetyService;
    private final NearbyServiceSearchService nearbyServiceSearchService;
    private final DiseaseRecognitionService diseaseRecognitionService;
    private final UserSessionService userSessionService;

    public SpringAiTools(WeatherService weatherService,
                         WebSearchTool webSearchTool,
                         TtsTool ttsTool,
                         ImageAnalysisTool imageAnalysisTool,
                         ImageGenerationTool imageGenerationTool,
                         ImageEditTool imageEditTool,
                         FileAnalysisTool fileAnalysisTool,
                         CareReminderService careReminderService,
                         CareRecordService careRecordService,
                         CareAdvancedService careAdvancedService,
                         PetCareQueryService petCareQueryService,
                         PlantSafetyQueryService plantSafetyQueryService,
                         PetFoodSafetyService petFoodSafetyService,
                         NearbyServiceSearchService nearbyServiceSearchService,
                         DiseaseRecognitionService diseaseRecognitionService,
                         UserSessionService userSessionService) {
        this.weatherService = weatherService;
        this.webSearchTool = webSearchTool;
        this.ttsTool = ttsTool;
        this.imageAnalysisTool = imageAnalysisTool;
        this.imageGenerationTool = imageGenerationTool;
        this.imageEditTool = imageEditTool;
        this.fileAnalysisTool = fileAnalysisTool;
        this.careReminderService = careReminderService;
        this.careRecordService = careRecordService;
        this.careAdvancedService = careAdvancedService;
        this.petCareQueryService = petCareQueryService;
        this.plantSafetyQueryService = plantSafetyQueryService;
        this.petFoodSafetyService = petFoodSafetyService;
        this.nearbyServiceSearchService = nearbyServiceSearchService;
        this.diseaseRecognitionService = diseaseRecognitionService;
        this.userSessionService = userSessionService;
    }

    @PostConstruct
    public void init() {
        log.info("SpringAiTools initialized with care services");
    }

    @Tool(name = "getCurrentTime", description = "获取当前系统时间")
    public String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("getCurrentTime called, returning: {}", formatted);
        return formatted;
    }

    @Tool(name = "getWeather", description = "查询指定城市的实时天气信息")
    public WeatherResponse getWeather(@ToolParam(description = "城市名称，如：北京、上海、杭州") String city) {
        log.info("getWeather called for city: {}", city);
        try {
            WeatherResponse response = weatherService.getWeatherByCity(city);
            log.info("Weather retrieved successfully for city: {}", city);
            return response;
        } catch (Exception e) {
            log.error("Failed to get weather for city: {}", city, e);
            throw new RuntimeException("获取天气失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "webSearch", description = "联网搜索工具，获取实时新闻、天气、百科等在线信息。当用户询问需要联网查询的内容时使用。")
    public String webSearch(
            @ToolParam(description = "搜索关键词，如：北京今天天气、最新科技新闻", required = true) String query) {
        log.info("[Tool] webSearch called, query: {}", query);
        try {
            JSONObject params = new JSONObject();
            params.put("query", query);
            ToolResult<?> result = webSearchTool.execute(params);
            if (result.isSuccess()) {
                return dataToString(result.getData());
            }
            return "搜索失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] webSearch error: {}", e.getMessage(), e);
            return "搜索工具异常：" + e.getMessage();
        }
    }

    @Tool(name = "synthesizeSpeech", description = "语音合成工具，将文本内容转换为语音文件。适用于需要朗读、播报、语音回复等场景。")
    public String synthesizeSpeech(
            @ToolParam(description = "需要合成为语音的文本内容", required = true) String text) {
        log.info("[Tool] synthesizeSpeech called, text length: {}", text != null ? text.length() : 0);
        try {
            JSONObject params = new JSONObject();
            params.put("text", text);
            ToolResult<?> result = ttsTool.execute(params);
            if (result.isSuccess()) {
                return "[AUDIO:" + dataToString(result.getData()) + "]";
            }
            return "语音合成失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] synthesizeSpeech error: {}", e.getMessage(), e);
            return "语音合成工具异常：" + e.getMessage();
        }
    }

    @Tool(name = "generateImage", description = "AI图片生成工具，根据文字描述生成图片。适用于需要创作插画、海报、概念图等场景。")
    public String generateImage(
            @ToolParam(description = "图片的文字描述，越详细越好，如：一只可爱的橘猫在阳光下的窗台上", required = true) String prompt,
            @ToolParam(description = "图片风格，可选值：realistic（写实）、anime（动漫）、oil-painting（油画）、watercolor（水彩）") String style) {
        log.info("[Tool] generateImage called, prompt: {}, style: {}", prompt, style);
        try {
            JSONObject params = new JSONObject();
            params.put("prompt", prompt);
            if (style != null && !style.isBlank()) {
                params.put("style", style);
            }
            ToolResult<?> result = imageGenerationTool.execute(params);
            if (result.isSuccess()) {
                return "[IMAGE:" + dataToString(result.getData()) + "]";
            }
            return "图片生成失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] generateImage error: {}", e.getMessage(), e);
            return "图片生成工具异常：" + e.getMessage();
        }
    }

    @Tool(name = "analyzeImage", description = "图片分析工具，分析用户上传的图片内容。可以识别物体、描述场景、提取文字等。用户需要先上传图片才能使用此工具。")
    public String analyzeImage(
            @ToolParam(description = "分析指令，如：描述图片内容、识别图片中的文字、列出图片中的物体", required = true) String prompt) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] analyzeImage called, userId: {}, prompt: {}", userId, prompt);
        try {
            JSONObject params = new JSONObject();
            params.put("prompt", prompt);
            if (userId != null && !userId.isBlank()) {
                params.put("userId", userId);
            }
            ToolResult<?> result = imageAnalysisTool.execute(params);
            if (result.isSuccess()) {
                return dataToString(result.getData());
            }
            return "图片分析失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] analyzeImage error: {}", e.getMessage(), e);
            return "图片分析工具异常：" + e.getMessage();
        }
    }

    @Tool(name = "editImage", description = "图片编辑工具，对用户上传的图片进行修改、编辑。适用于图片优化、风格转换等场景。")
    public String editImage(
            @ToolParam(description = "编辑指令，如：将图片转为水彩风格、增强图片对比度", required = true) String prompt) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] editImage called, userId: {}, prompt: {}", userId, prompt);
        try {
            JSONObject params = new JSONObject();
            params.put("prompt", prompt);
            if (userId != null && !userId.isBlank()) {
                params.put("userId", userId);
            }
            ToolResult<?> result = imageEditTool.execute(params);
            if (result.isSuccess()) {
                return "[IMAGE:" + dataToString(result.getData()) + "]";
            }
            return "图片编辑失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] editImage error: {}", e.getMessage(), e);
            return "图片编辑工具异常：" + e.getMessage();
        }
    }

    @Tool(name = "analyzeFile", description = "文档解析工具，分析用户上传的文档（PDF、Word、Excel、PPT等）内容。可以提取文本、生成摘要、分析数据等。")
    public String analyzeFile(
            @ToolParam(description = "分析指令，如：提取文档摘要、分析表格数据、提取关键信息", required = true) String prompt) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] analyzeFile called, userId: {}, prompt: {}", userId, prompt);
        try {
            JSONObject params = new JSONObject();
            params.put("prompt", prompt);
            if (userId != null && !userId.isBlank()) {
                params.put("userId", userId);
            }
            ToolResult<?> result = fileAnalysisTool.execute(params);
            if (result.isSuccess()) {
                return dataToString(result.getData());
            }
            return "文档解析失败：" + result.getMessage();
        } catch (Exception e) {
            log.error("[Tool] analyzeFile error: {}", e.getMessage(), e);
            return "文档解析工具异常：" + e.getMessage();
        }
    }

    // ============ 护理提醒工具 ============

    @Tool(name = "createCareReminder", description = "创建护理提醒工具，设置浇水、施肥、驱虫、疫苗、喂药等定时提醒。支持每日、每周、每月重复。")
    public String createCareReminder(
            @ToolParam(description = "目标类型：pet（宠物）或 plant（植物）", required = true) String targetType,
            @ToolParam(description = "目标ID，如宠物或植物的档案ID", required = true) Long targetId,
            @ToolParam(description = "提醒类型：浇水、施肥、驱虫、疫苗、喂药、其他", required = true) String reminderType,
            @ToolParam(description = "提醒内容详情", required = true) String content,
            @ToolParam(description = "提醒时间，格式：yyyy-MM-dd HH:mm，如 2026-07-30 09:00", required = true) String dueAt,
            @ToolParam(description = "重复规则：DAILY（每日）、WEEKLY（每周）、MONTHLY（每月），可留空") String repeatRule) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] createCareReminder called, userId: {}, type: {}, reminder: {}", userId, targetType, reminderType);
        if (userId == null || userId.isBlank()) {
            return "请先登录后再创建提醒";
        }
        return careReminderService.createReminder(userId, targetType, targetId, reminderType, content, dueAt, repeatRule);
    }

    @Tool(name = "completeCareReminder", description = "完成护理提醒工具，用户回复'已完成'后调用，自动写入护理记录并创建下次重复提醒。")
    public String completeCareReminder() {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] completeCareReminder called, userId: {}", userId);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        return careReminderService.completeLatestReminder(userId);
    }

    @Tool(name = "listCareReminders", description = "查看当前用户的所有护理提醒列表。")
    public String listCareReminders() {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] listCareReminders called, userId: {}", userId);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        var reminders = careReminderService.getUserReminders(userId);
        if (reminders.isEmpty()) {
            return "暂无护理提醒。";
        }
        StringBuilder sb = new StringBuilder("【护理提醒列表】\n");
        for (var r : reminders) {
            sb.append("- ").append(r.get("reminder_type")).append("：")
                    .append(r.get("content")).append(" [")
                    .append(r.get("status")).append("] ")
                    .append(r.get("due_at")).append("\n");
        }
        return sb.toString();
    }

    // ============ 宠物护理查询工具 ============

    @Tool(name = "queryPetCare", description = "查询宠物养护专业知识。覆盖：喂养指南、常见疾病症状和家庭处理、疫苗接种计划、品种特征、行为训练、日常护理。适用于猫、狗、仓鼠、兔子、鸟类、鱼类、爬宠等。")
    public String queryPetCare(
            @ToolParam(description = "问题类型：feeding(喂养)/disease(疾病)/vaccine(疫苗)/breed(品种)/training(训练)/care(护理)/behavior(行为)/emergency(急救)/other(其他)") String queryType,
            @ToolParam(description = "宠物类型，例如：猫、狗、仓鼠、兔子、鸟、鱼、乌龟") String petType,
            @ToolParam(description = "用户的具体问题，例如：猫咪吐黄水怎么办、狗狗多大打疫苗", required = true) String question) {
        log.info("[Tool] queryPetCare called, type: {}, pet: {}, question: {}", queryType, petType, question);
        return petCareQueryService.queryPetCare(queryType, petType, question);
    }

    // ============ 植物宠物毒性工具 ============

    @Tool(name = "queryPlantSafety", description = "查询植物对宠物的毒性信息。覆盖：常见家养植物对猫/狗/兔子等的毒性、误食症状、应急处理、宠物友好型植物推荐。")
    public String queryPlantSafety(
            @ToolParam(description = "问题类型：toxicity(毒性查询)/symptoms(误食症状)/emergency(应急处理)/safe_plants(安全植物推荐)/identify(植物识别)/other(其他)") String queryType,
            @ToolParam(description = "植物名称，例如：百合、绿萝、龟背竹、滴水观音、郁金香") String plantName,
            @ToolParam(description = "用户的具体问题，例如：百合对猫有毒吗、狗吃了绿萝会怎样", required = true) String question) {
        log.info("[Tool] queryPlantSafety called, type: {}, plant: {}, question: {}", queryType, plantName, question);
        return plantSafetyQueryService.queryPlantSafety(queryType, plantName, question);
    }

    // ============ 宠物食品安全工具 ============

    @Tool(name = "queryFoodSafety", description = "查询某种食物猫狗是否能吃。给出安全等级、建议分量和中毒症状。误食危险食物时自动进入急症流程。")
    public String queryFoodSafety(
            @ToolParam(description = "食物名称，例如：巧克力、葡萄、洋葱、大蒜、牛奶", required = true) String foodName,
            @ToolParam(description = "宠物类型，如：猫、狗，默认为猫") String petType) {
        log.info("[Tool] queryFoodSafety called, food: {}, pet: {}", foodName, petType);
        return petFoodSafetyService.queryFoodSafety(foodName, petType);
    }

    // ============ 症状分诊工具 ============

    @Tool(name = "triageSymptoms", description = "根据症状、持续时间、年龄判断紧急程度。输出'立即就医、24小时内就医、继续观察'。不替代兽医诊断。")
    public String triageSymptoms(
            @ToolParam(description = "症状描述，如：呼吸困难、抽搐、持续呕吐", required = true) String symptoms,
            @ToolParam(description = "症状持续时间，如：2小时、1天") String duration,
            @ToolParam(description = "宠物年龄，如：3个月、2岁") String age) {
        log.info("[Tool] triageSymptoms called, symptoms: {}, duration: {}, age: {}", symptoms, duration, age);
        return careAdvancedService.triage(symptoms, duration, age);
    }

    // ============ 用药与疫苗记录工具 ============

    @Tool(name = "saveMedication", description = "保存用药与疫苗记录。保存药名、剂量、时间和疗程。剂量必须由兽医确认。")
    public String saveMedication(
            @ToolParam(description = "目标类型：pet（宠物）或 plant（植物）", required = true) String targetType,
            @ToolParam(description = "目标ID", required = true) Long targetId,
            @ToolParam(description = "药品名称和剂量，如：阿莫西林 250mg", required = true) String medicine,
            @ToolParam(description = "用法说明，如：每日2次，饭后服用", required = true) String instruction,
            @ToolParam(description = "处方来源，如：张三兽医", required = true) String prescribedBy) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] saveMedication called, userId: {}, medicine: {}", userId, medicine);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        return careRecordService.saveMedication(userId, targetType, targetId, medicine, instruction, prescribedBy).getContent();
    }

    @Tool(name = "checkMedication", description = "检查用药记录，查看是否有漏服、重复用药和疫苗到期情况。")
    public String checkMedication() {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] checkMedication called, userId: {}", userId);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        return careRecordService.checkMedicationReminders(userId);
    }

    // ============ 图片变化对比工具 ============

    @Tool(name = "compareImages", description = "对比植物或宠物的图片变化。对比叶色、生长状态、病斑、皮肤、伤口、体型和毛发变化。")
    public String compareImages(
            @ToolParam(description = "对比类型：plant（植物）或 pet（宠物）", required = true) String type) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] compareImages called, userId: {}, type: {}", userId, type);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        return careRecordService.compareImages(userId, type);
    }

    // ============ 智能护理计划工具 ============

    @Tool(name = "generateCarePlan", description = "根据品种、年龄、季节、天气生成护理周计划。自动拆分为每日任务。完成情况写入档案。")
    public String generateCarePlan(
            @ToolParam(description = "目标类型：pet（宠物）或 plant（植物）", required = true) String targetType,
            @ToolParam(description = "目标ID", required = true) Long targetId,
            @ToolParam(description = "品种，如：金毛、英短、多肉、绿萝") String breed,
            @ToolParam(description = "年龄，如：2岁、6个月") String age,
            @ToolParam(description = "季节，如：春季、夏季、秋季、冬季") String season,
            @ToolParam(description = "天气情况，如：高温、寒潮、晴朗") String weather) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] generateCarePlan called, userId: {}, breed: {}", userId, breed);
        if (userId == null || userId.isBlank()) {
            return "请先登录";
        }
        return careRecordService.generateCarePlan(userId, targetType, targetId, breed, age, season, weather).getContent();
    }

    // ============ 天气预警工具 ============

    @Tool(name = "weatherAlert", description = "天气预警工具。根据天气情况给出高温、寒潮、暴雨、空气干燥等预警，并调整浇水、遛宠和通风方案。")
    public String weatherAlert(
            @ToolParam(description = "城市名称", required = true) String city,
            @ToolParam(description = "护理场景，如：浇水、遛宠、通风", required = true) String scene) {
        log.info("[Tool] weatherAlert called, city: {}, scene: {}", city, scene);
        return careAdvancedService.weatherAlert(city, scene);
    }

    // ============ 附近服务搜索工具 ============

    @Tool(name = "searchNearbyService", description = "查找附近宠物医院、急诊、植物医院和园艺店。返回结果末尾有【必须保留的导航链接】段落，你必须原样输出这些URL链接，绝对不能省略、改写或总结它们。")
    public String searchNearbyService(
            @ToolParam(description = "服务类型：hospital(宠物医院)/emergency(24小时急诊)/clinic(诊所)/plant_hospital(植物医院)/gardening(园艺店)/pet_shop(宠物店)/grooming(美容)") String serviceType,
            @ToolParam(description = "位置，如：北京市朝阳区", required = true) String location) {
        log.info("[Tool] searchNearbyService called, type: {}, location: {}", serviceType, location);
        return nearbyServiceSearchService.searchNearbyService(serviceType, location);
    }

    // ============ 通用专业查询 ============

    @Tool(name = "professionalSearch", description = "联网专业搜索，获取宠物护理、植物养护等专业信息。")
    public String professionalSearch(
            @ToolParam(description = "搜索关键词", required = true) String query) {
        log.info("[Tool] professionalSearch called, query: {}", query);
        return careAdvancedService.professionalSearch(query);
    }

    // ============ 病害视觉诊断工具 ============

    @Tool(name = "diagnoseDisease", description = "植物病虫害/宠物皮肤病视觉诊断工具。上传植物叶子或宠物皮肤照片，AI识别病害类型并给出治疗方案。")
    public String diagnoseDisease(
            @ToolParam(description = "诊断类型：plant（植物病虫害）或 pet（宠物皮肤病）", required = true) String type) {
        String userId = UserContextHolder.getUserId();
        log.info("[Tool] diagnoseDisease called, userId: {}, type: {}", userId, type);
        if (userId == null || userId.isBlank()) {
            return "请先登录后再使用病害诊断功能。";
        }

        String pendingImageBase64 = userSessionService.getPendingImageBase64(userId);
        if (pendingImageBase64 == null || pendingImageBase64.isBlank()) {
            return "请先上传植物叶片或宠物皮肤的照片，然后再发起病害诊断请求。";
        }

        try {
            byte[] imageBytes = Base64.getDecoder().decode(pendingImageBase64);
            DiseaseResult result = diseaseRecognitionService.diagnose(imageBytes, type, userId, null);

            StringBuilder sb = new StringBuilder();
            sb.append("【病害诊断结果】\n\n");
            sb.append("病害名称：").append(defaultIfEmpty(result.getDiseaseName())).append("\n");
            sb.append("置信度：").append(defaultIfEmpty(result.getConfidence())).append("\n");
            sb.append("症状描述：").append(defaultIfEmpty(result.getSymptoms())).append("\n");
            sb.append("治疗方案：").append(defaultIfEmpty(result.getTreatmentPlan())).append("\n");
            sb.append("预防建议：").append(defaultIfEmpty(result.getPrevention())).append("\n");
            sb.append("紧急程度：").append(defaultIfEmpty(result.getUrgencyLevel())).append("\n");
            sb.append("\n注意：AI辅助诊断不能替代专业医生/兽医诊断，严重情况请及时就医。");

            return sb.toString();
        } catch (Exception e) {
            log.error("[Tool] diagnoseDisease error: {}", e.getMessage(), e);
            return "病害诊断失败：" + e.getMessage();
        }
    }

    private String defaultIfEmpty(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private String dataToString(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof String) {
            return (String) data;
        }
        return JSON.toJSONString(data);
    }
}