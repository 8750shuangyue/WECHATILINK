package com.example.demo.care.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.agent.tools.WebSearchTool;
import com.example.demo.care.model.CareRecord;
import com.example.demo.care.model.CareTarget;
import com.example.demo.care.model.IdentifyHistory;
import com.example.demo.care.repository.CareRecordRepository;
import com.example.demo.care.repository.CareTargetRepository;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import com.example.demo.weather.model.WeatherResponse;
import com.example.demo.weather.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CareAdvancedService {

    private static final Logger log = LoggerFactory.getLogger(CareAdvancedService.class);

    private final JdbcTemplate jdbc;
    private final WebSearchTool webSearchTool;
    private final CareRecordRepository careRecordRepository;
    private final CareTargetRepository careTargetRepository;
    private final IdentifyHistoryRepository identifyHistoryRepository;
    private final WeatherService weatherService;

    public CareAdvancedService(JdbcTemplate jdbc,
                               WebSearchTool webSearchTool,
                               CareRecordRepository careRecordRepository,
                               CareTargetRepository careTargetRepository,
                               IdentifyHistoryRepository identifyHistoryRepository,
                               WeatherService weatherService) {
        this.jdbc = jdbc;
        this.webSearchTool = webSearchTool;
        this.careRecordRepository = careRecordRepository;
        this.careTargetRepository = careTargetRepository;
        this.identifyHistoryRepository = identifyHistoryRepository;
        this.weatherService = weatherService;
    }

    // ============ 症状分诊 ============

    public String triage(String symptoms, String duration, String age) {
        String value = symptoms == null ? "" : symptoms;

        if (value.matches(".*(呼吸困难|抽搐|无法排尿|昏迷|大量出血|误食.*毒|持续呕吐).*")) {
            return "紧急级别：立即就医。不要自行喂药或催吐；保持呼吸道通畅，携带误食物包装和既往记录前往宠物急诊。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("需要进一步评估。");
        if (symptoms != null && !symptoms.isBlank()) {
            sb.append("症状：").append(symptoms).append("；");
        }
        if (duration != null && !duration.isBlank()) {
            sb.append("持续时间：").append(duration).append("；");
        }
        if (age != null && !age.isBlank()) {
            sb.append("年龄：").append(age).append("；");
        }
        sb.append("请结合精神、饮食、排泄、体温等信息，必要时24小时内咨询兽医。");

        return sb.toString();
    }

    // ============ 用药与记录 ============

    public String saveMedication(String userId, String targetType, Long targetId,
                                 String medicine, String instruction, String prescribedBy) {
        if (targetId == null) {
            return "请先选择具体档案，再保存用药记录。";
        }

        CareRecord record = new CareRecord();
        record.setUserId(userId);
        record.setTargetType(CareRecord.TargetType.valueOf(targetType.toUpperCase()));
        record.setTargetId(targetId);
        record.setRecordType(CareRecord.RecordType.MEDICATION);
        record.setContent(medicine + "；用法：" + instruction + "；处方来源：" + prescribedBy);
        careRecordRepository.save(record);

        return "用药记录已保存。剂量与疗程请严格遵循兽医处方。";
    }

    public String saveCarePlan(String userId, String targetType, Long targetId, String plan) {
        if (targetId == null) {
            return "请先选择具体档案，再保存护理计划。";
        }

        CareRecord record = new CareRecord();
        record.setUserId(userId);
        record.setTargetType(CareRecord.TargetType.valueOf(targetType.toUpperCase()));
        record.setTargetId(targetId);
        record.setRecordType(CareRecord.RecordType.CARE);
        record.setContent("护理计划：" + plan);
        careRecordRepository.save(record);

        return "护理计划已保存到档案。";
    }

    // ============ 图片变化对比（增强版） ============

    public String compareRecentImages(String userId, String type) {
        try {
            List<IdentifyHistory> histories = identifyHistoryRepository
                    .findByUserIdAndIdentifyTypeOrderByCreatedAtDesc(userId, type);

            if (histories.size() < 2) {
                return "至少需要上传并识别两张不同时间的图片才能对比。当前已上传 " + histories.size() + " 张。";
            }

            IdentifyHistory latest = histories.get(0);
            IdentifyHistory previous = histories.get(1);

            StringBuilder sb = new StringBuilder();
            sb.append("【图片变化对比报告】\n\n");

            sb.append("📸 最新识别（").append(formatTime(latest.getCreatedAt())).append("）：\n");
            sb.append(latest.getResult() != null ? latest.getResult() : "无记录").append("\n\n");

            sb.append("📸 上一次识别（").append(formatTime(previous.getCreatedAt())).append("）：\n");
            sb.append(previous.getResult() != null ? previous.getResult() : "无记录").append("\n\n");

            if (latest.getMetadata() != null && previous.getMetadata() != null) {
                try {
                    JSONObject latestMeta = JSON.parseObject(latest.getMetadata());
                    JSONObject prevMeta = JSON.parseObject(previous.getMetadata());
                    sb.append(compareMetadata(latestMeta, prevMeta));
                } catch (Exception e) {
                    log.debug("Metadata comparison skipped: {}", e.getMessage());
                }
            }

            sb.append("请重点关注外观、健康状态、叶色/毛发、病斑或体态的变化趋势。");
            return sb.toString();
        } catch (Exception e) {
            log.error("[Care] Error comparing images: {}", e.getMessage(), e);
            return "图片对比失败：" + e.getMessage();
        }
    }

    private String compareMetadata(JSONObject latest, JSONObject previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 特征指标对比：\n");

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(latest.keySet());
        keys.addAll(previous.keySet());

        for (String key : keys) {
            Object val1 = latest.get(key);
            Object val2 = previous.get(key);
            if (val1 != null && val2 != null && !val1.equals(val2)) {
                sb.append("- ").append(key).append(": ").append(val2)
                  .append(" → ").append(val1).append(" ✅ 有变化\n");
            }
        }

        if (sb.length() == 0) {
            return "";
        }
        return sb.toString();
    }

    private String formatTime(LocalDateTime time) {
        if (time == null) return "";
        return time.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    // ============ 天气预警（增强版 - 基于真实天气 API） ============

    public String weatherAlert(String city, String scene) {
        try {
            StringBuilder sb = new StringBuilder();

            WeatherResponse weather = weatherService.getWeatherByCity(city);
            sb.append("📍 ").append(weather.getCity()).append("天气：");

            if (weather.getCurrent() != null) {
                sb.append(weather.getCurrent().getWeather())
                  .append("，气温 ").append(weather.getCurrent().getTemperature()).append("°C");

                if (weather.getCurrent().getHumidity() != null) {
                    sb.append("，湿度 ").append(weather.getCurrent().getHumidity()).append("%");
                }
                if (weather.getCurrent().getWindSpeed() != null) {
                    sb.append("，风速 ").append(weather.getCurrent().getWindSpeed()).append("km/h");
                }

                sb.append("\n\n⚠️ 护理预警：\n");
                sb.append(generateWeatherAdvisory(weather, scene));
            }

            if (weather.getForecast() != null && !weather.getForecast().isEmpty()) {
                sb.append("\n📅 未来天气预报：\n");
                int days = Math.min(3, weather.getForecast().size());
                for (int i = 0; i < days; i++) {
                    WeatherResponse.ForecastDay day = weather.getForecast().get(i);
                    sb.append("- ").append(day.getDate() != null ? day.getDate().substring(5) : "")
                      .append(" ").append(day.getDayWeather() != null ? day.getDayWeather() : "")
                      .append(" ").append(day.getHighTemp() != null ? day.getHighTemp() + "°C" : "")
                      .append("/").append(day.getLowTemp() != null ? day.getLowTemp() + "°C" : "")
                      .append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Weather API failed, falling back to web search: {}", e.getMessage());
            return weatherAlertFallback(city, scene);
        }
    }

    private String generateWeatherAdvisory(WeatherResponse weather, String scene) {
        StringBuilder sb = new StringBuilder();
        WeatherResponse.CurrentWeather cur = weather.getCurrent();

        if (cur == null) return "天气数据不足，无法生成预警。";

        double temp = cur.getTemperature() != null ? cur.getTemperature() : 20;
        double humidity = cur.getHumidity() != null ? cur.getHumidity() : 50;
        String weatherDesc = cur.getWeather() != null ? cur.getWeather() : "";

        boolean isPet = scene != null && scene.contains("宠物");
        boolean isPlant = scene != null && scene.contains("植物");

        List<String> warnings = new ArrayList<>();

        // 高温预警
        if (temp >= 35) {
            warnings.add("🔥 高温预警：避免正午户外活动（10:00-16:00），增加饮水，注意防晒");
            if (isPet) warnings.add("宠物避暑：提供充足饮水，剪短毛发，避免水泥地烫伤脚掌");
            if (isPlant) warnings.add("植物防暑：增加浇水频率至早晚各一次，遮阴处理");
        } else if (temp >= 30) {
            warnings.add("☀️ 天气炎热：注意补水，观察精神状态");
            if (isPlant) warnings.add("植物：早晚浇水，避免中午暴晒");
        }

        // 低温预警
        if (temp <= 5) {
            warnings.add("❄️ 寒潮预警：注意保暖，减少外出时间");
            if (isPet) warnings.add("宠物保暖：提供毛毯，避免户外久留，注意防冻伤");
            if (isPlant) warnings.add("植物防寒：移至室内或覆盖保温材料，减少浇水");
        } else if (temp <= 10) {
            warnings.add("🌡️ 天气较冷：注意保暖，适当增加营养");
        }

        // 雨天预警
        if (weatherDesc.contains("雨")) {
            warnings.add("🌧️ 雨天提醒：关好门窗，保持干燥");
            if (isPet) warnings.add("宠物：减少户外散步，注意防滑，及时吹干毛发");
            if (isPlant) warnings.add("植物：减少浇水，注意排水，避免积水烂根");
        }

        // 湿度预警
        if (humidity >= 85) {
            warnings.add("💧 高湿度预警：注意防潮，防止霉菌滋生");
            if (isPlant) warnings.add("植物：减少浇水，加强通风，防止病害");
        } else if (humidity <= 30) {
            warnings.add("🏜️ 空气干燥：注意补水保湿");
            if (isPlant) warnings.add("植物：增加浇水频率，可使用加湿器");
        }

        // 大风预警
        if (cur.getWindSpeed() != null && cur.getWindSpeed() >= 40) {
            warnings.add("🌬️ 大风预警：户外活动注意安全，关好门窗");
        }

        // 如果没有预警，给出日常建议
        if (warnings.isEmpty()) {
            warnings.add("✅ 天气适宜，适合日常护理和户外活动");
            warnings.add("保持规律的饮食、运动和清洁习惯");
        }

        return String.join("\n", warnings);
    }

    private String weatherAlertFallback(String city, String scene) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", city + "天气");
            ToolResult<?> result = webSearchTool.execute(params);

            StringBuilder sb = new StringBuilder();
            if (result.isSuccess()) {
                sb.append(result.getData()).append("\n");
            }
            sb.append("护理场景：").append(scene);

            return sb.toString();
        } catch (Exception e) {
            return "天气预警查询失败：" + e.getMessage();
        }
    }

    // ============ 智能护理计划（增强版） ============

    public String generateCarePlan(String userId, String targetType, Long targetId,
                                    String breed, String age, String season, String weather) {
        StringBuilder plan = new StringBuilder();
        plan.append("【智能护理计划】\n");
        plan.append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");

        if (breed != null) plan.append("品种：").append(breed).append("\n");
        if (age != null) plan.append("年龄阶段：").append(age).append("\n");
        if (season != null) plan.append("当前季节：").append(season).append("\n");
        if (weather != null) plan.append("天气条件：").append(weather).append("\n");

        plan.append("\n📅 每周护理计划：\n");

        boolean isPlant = "plant".equalsIgnoreCase(targetType);
        boolean isPet = "pet".equalsIgnoreCase(targetType);

        if (isPlant) {
            plan.append(generatePlantPlan(breed, age, season, weather));
        } else if (isPet) {
            plan.append(generatePetPlan(breed, age, season, weather));
        } else {
            plan.append(generateGeneralPlan(season));
        }

        if (targetId != null) {
            saveCarePlan(userId, targetType, targetId, plan.toString());
        }

        return plan.toString();
    }

    private String generatePlantPlan(String species, String age, String season, String weather) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n🌿 植物专项护理：\n");
        if (species != null) {
            sb.append("品种：").append(species).append("，");
            sb.append(getPlantSpeciesCareTips(species)).append("\n");
        }

        if (age != null) {
            sb.append("年龄阶段：").append(age).append("，");
            sb.append(getPlantAgeCareTips(age)).append("\n");
        }

        if (season != null) {
            sb.append("季节护理：").append(getPlantSeasonCare(season)).append("\n");
        }

        sb.append("\n📋 每日任务清单：\n");
        sb.append("周一：检查土壤湿度，基础浇水\n");
        sb.append("周二：观察叶片状态，清理黄叶\n");
        sb.append("周三：适量光照， Rotate 受光均匀\n");
        sb.append("周四：检查虫害，预防病害\n");
        sb.append("周五：清洁叶面，保持通风\n");
        sb.append("周六：适量施肥（生长期）\n");
        sb.append("周日：整体评估，调整护理方案\n");

        if (weather != null) {
            sb.append("\n🌦️ 天气适配：").append(getPlantWeatherTip(weather));
        }

        return sb.toString();
    }

    private String generatePetPlan(String breed, String age, String season, String weather) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n🐾 宠物专项护理：\n");
        if (breed != null) {
            sb.append("品种：").append(breed).append("，");
            sb.append(getBreedCareTips(breed)).append("\n");
        }

        if (age != null) {
            sb.append("年龄阶段：").append(age).append("，");
            sb.append(getPetAgeCareTips(age)).append("\n");
        }

        if (season != null) {
            sb.append("季节护理：").append(getPetSeasonCare(season)).append("\n");
        }

        sb.append("\n📋 每日任务清单：\n");
        sb.append("周一：基础护理（喂食、清洁）\n");
        sb.append("周二：健康检查、适度运动\n");
        sb.append("周三：毛发梳理、口腔清洁\n");
        sb.append("周四：户外散步、社交活动\n");
        sb.append("周五：深度护理、环境消毒\n");
        sb.append("周六：技能训练、互动游戏\n");
        sb.append("周日：自由活动、行为观察总结\n");

        if (weather != null) {
            sb.append("\n🌦️ 天气适配：").append(getPetWeatherTip(weather));
        }

        return sb.toString();
    }

    private String generateGeneralPlan(String season) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📋 基础护理计划：\n");
        sb.append("周一：基础护理（喂食、清洁）\n");
        sb.append("周三：健康检查、适度运动\n");
        sb.append("周五：深度护理、环境消毒\n");
        sb.append("周日：自由活动、行为观察\n");

        if (season != null) {
            sb.append("\n季节提示：").append(season).append("期间注意相应的护理调整");
        }

        return sb.toString();
    }

    // ============ 品种/年龄/季节 知识库 ============

    private String getPlantSpeciesCareTips(String species) {
        String s = species.toLowerCase();
        if (s.contains("多肉") || s.contains("succulent") || s.contains("仙人掌")) {
            return "多肉植物：耐旱，7-10天浇一次水，避免积水";
        }
        if (s.contains("绿萝") || s.contains("pothos")) {
            return "绿萝：喜湿润，3-5天浇一次水，定期擦拭叶面";
        }
        if (s.contains("月季") || s.contains("玫瑰") || s.contains("rose")) {
            return "月季：喜阳光充足，每2-3天浇水，定期修剪";
        }
        if (s.contains("兰花") || s.contains("orchid")) {
            return "兰花：忌水多，5-7天浇一次，使用兰花专用肥";
        }
        if (s.contains("薄荷") || s.contains("mint")) {
            return "薄荷：喜湿润阳光充足，2-3天浇一次，经常修剪";
        }
        return "请根据具体品种习性调整浇水和光照";
    }

    private String getPlantAgeCareTips(String age) {
        if (age.contains("幼年") || age.contains("幼") || age.contains("seedling")) {
            return "幼苗期：避免强光和过量浇水，保持湿润通风";
        }
        if (age.contains("老年") || age.contains("senior")) {
            return "老年期：减少施肥频率，注意保暖，定期修剪枯枝";
        }
        return "成年期：按常规养护周期进行护理";
    }

    private String getPlantSeasonCare(String season) {
        return switch (season) {
            case "春季" -> "春季：生长旺季，增加浇水频率，每月施肥1-2次";
            case "夏季" -> "夏季：避免正午暴晒，早晚浇水，注意遮阴";
            case "秋季" -> "秋季：减少浇水，为越冬做准备，可进行扦插繁殖";
            case "冬季" -> "冬季：减少浇水，停止施肥，保持室温10°C以上";
            default -> "请根据季节调整浇水和光照策略";
        };
    }

    private String getPlantWeatherTip(String weather) {
        if (weather.contains("雨")) return "雨天减少浇水，注意排水，避免积水";
        if (weather.contains("高温") || weather.contains("热")) return "高温天增加浇水频率至早晚各一次，遮阴处理";
        if (weather.contains("冷") || weather.contains("寒")) return "寒潮来临前移至室内，减少浇水";
        if (weather.contains("干燥")) return "干燥天气增加浇水，使用加湿器";
        return "根据天气适当调整浇水和光照";
    }

    private String getBreedCareTips(String breed) {
        String b = breed.toLowerCase();
        if (b.contains("金毛") || b.contains("拉布拉多") || b.contains("golden")) {
            return "金毛/拉布拉多：长毛需每日梳理，注意皮肤护理，每天至少1小时运动";
        }
        if (b.contains("柯基") || b.contains("corgi")) {
            return "柯基：注意腰部护理，避免跳跃，控制体重预防椎间盘问题";
        }
        if (b.contains("泰迪") || b.contains("贵宾") || b.contains("poodle")) {
            return "泰迪/贵宾：定期美容修剪，注意口腔护理，智商高需智力游戏";
        }
        if (b.contains("柴犬") || b.contains("shiba")) {
            return "柴犬：独立倔强性格，需要耐心训练，注意毛发护理";
        }
        if (b.contains("英短") || b.contains("美短") || b.contains("british")) {
            return "英短/美短：注意控制体重，预防肥胖相关疾病";
        }
        if (b.contains("布偶") || b.contains("ragdoll")) {
            return "布偶：长毛需每日梳理，性格温顺，注意眼睛护理";
        }
        if (b.contains("中华田园") || b.contains("土狗")) {
            return "中华田园犬：体质强健，按常规护理即可，注意定期驱虫和疫苗";
        }
        return "请根据品种特点调整护理方案";
    }

    private String getPetAgeCareTips(String age) {
        if (age.contains("幼年") || age.contains("幼") || age.contains("kitten") || age.contains("puppy")) {
            return "幼年期：少食多餐（3-4次/天），疫苗接种期，注意保暖和社会化训练";
        }
        if (age.contains("青年") || age.contains("young")) {
            return "青年期：运动量最大，注意营养均衡，加强训练";
        }
        if (age.contains("老年") || age.contains("senior") || age.contains("elderly")) {
            return "老年期：减少剧烈运动，补充关节保健品，定期体检，注意保暖";
        }
        if (age.contains("中年") || age.contains("adult")) {
            return "中年期：保持规律运动和饮食，预防肥胖，每年体检";
        }
        return "按年龄段特点进行针对性护理";
    }

    private String getPetSeasonCare(String season) {
        return switch (season) {
            case "春季" -> "春季：换毛季，增加梳毛频率，定期驱虫，注意花粉过敏";
            case "夏季" -> "夏季：防暑降温，避免正午户外运动，充足饮水，注意跳蚤蜱虫";
            case "秋季" -> "秋季：换毛季，增加梳毛，储备能量准备越冬，注意保暖";
            case "冬季" -> "冬季：注意保暖，减少户外活动，增加营养，预防冻伤";
            default -> "请根据季节调整户外时间和饮食";
        };
    }

    private String getPetWeatherTip(String weather) {
        if (weather.contains("雨")) return "雨天减少户外散步，注意防滑，回家后及时擦干";
        if (weather.contains("高温") || weather.contains("热")) return "高温天避免正午活动，提供充足饮水，注意中暑";
        if (weather.contains("冷") || weather.contains("寒") || weather.contains("雪")) return "寒潮天减少户外活动，注意保暖，穿宠物衣服";
        if (weather.contains("雾") || weather.contains("霾")) return "雾霾天减少户外运动，注意呼吸道保护";
        return "根据天气适当调整户外活动时间";
    }

    // ============ 用药检查 ============

    public String checkMedicationReminders(String userId) {
        try {
            List<Map<String, Object>> medications = jdbc.queryForList("""
                    SELECT id, target_type, target_id, content, created_at
                    FROM care_record
                    WHERE user_id=? AND record_type='MEDICATION'
                    ORDER BY created_at DESC LIMIT 10
                    """, userId);

            if (medications.isEmpty()) {
                return "暂无用药记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【用药记录检查】\n");
            for (Map<String, Object> med : medications) {
                sb.append("- ").append(med.get("content")).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "用药检查失败：" + e.getMessage();
        }
    }

    // ============ 专业搜索 ============

    public String professionalSearch(String query) {
        try {
            JSONObject params = new JSONObject();
            params.put("query", query);
            ToolResult<?> result = webSearchTool.execute(params);
            return result.isSuccess() ? String.valueOf(result.getData()) : "联网搜索失败：" + result.getMessage();
        } catch (Exception e) {
            return "专业查询失败：" + e.getMessage();
        }
    }
}