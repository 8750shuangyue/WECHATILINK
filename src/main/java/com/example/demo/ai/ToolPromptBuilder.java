package com.example.demo.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按功能分组生成默认系统提示词。
 * 只负责文本拼装，不参与工具注册与执行。
 */
final class ToolPromptBuilder {

    private static final Map<String, List<String>> TOOL_GROUPS = new LinkedHashMap<>();
    private static final Map<String, String> GROUP_GUIDANCE = new LinkedHashMap<>();

    static {
        TOOL_GROUPS.put("实时天气", List.of("getWeather", "queryWeather", "weatherAlert"));
        GROUP_GUIDANCE.put("实时天气", "用户询问当前/未来/特定地点天气、温度、空气质量等时使用；严禁凭记忆回答天气，必须调用工具获取实时数据。");

        TOOL_GROUPS.put("图像多模态", List.of("analyzeImage", "generateImage", "editImage", "compareImages"));
        GROUP_GUIDANCE.put("图像多模态", "区分「分析已有图」vs「从零生成」vs「基于已有图修改」；指代「这张图/刚才的图」时倾向 editImage。");

        TOOL_GROUPS.put("文档/文件分析", List.of("analyzeFile"));
        GROUP_GUIDANCE.put("文档/文件分析", "用户问题与已上传文件内容相关时使用，严禁用训练数据代替文件内容。");

        TOOL_GROUPS.put("语音合成", List.of("synthesizeSpeech"));
        GROUP_GUIDANCE.put("语音合成", "用户要求读出来/转成语音/朗读/TTS 时使用。");

        TOOL_GROUPS.put("联网搜索", List.of("webSearch", "professionalSearch"));
        GROUP_GUIDANCE.put("联网搜索", "涉及实时信息（新闻/股价/比分/政策）、时间敏感问题，或明确包含「搜索/查一下/网上怎么说」时使用。");

        TOOL_GROUPS.put("附近服务", List.of("searchNearbyService"));
        GROUP_GUIDANCE.put("附近服务", "查找附近宠物医院、24小时急诊、诊所、植物医院、宠物店、园艺店等服务时使用。");

        TOOL_GROUPS.put("时间查询", List.of("getCurrentTime"));
        GROUP_GUIDANCE.put("时间查询", "询问当前时间/日期时使用。");

        TOOL_GROUPS.put("护理/健康管理", List.of(
                "createCareReminder", "queryPetCare", "queryPlantSafety", "queryFoodSafety",
                "saveMedication", "generateCarePlan", "checkMedication", "completeCareReminder",
                "listCareReminders", "triageSymptoms", "diagnoseDisease"));
        GROUP_GUIDANCE.put("护理/健康管理", "宠物/植物养护、喂药、疫苗、症状诊断、用药记录、护理提醒等场景使用。出现车祸、呼吸困难、抽搐、中毒、大出血等急症时，必须先调用 triageSymptoms，不要先追问缺失参数；持续时间和年龄未知可传“未知”。");
    }

    private ToolPromptBuilder() {
    }

    /** 提示词里出现过的工具名，供口径测试使用。 */
    static Set<String> groupedToolNames() {
        return TOOL_GROUPS.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
    }

    static String build(Set<String> allowedToolNames, Set<String> registeredTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 角色与核心目标\n");
        sb.append("你是具备多模态感知能力的智能助手。你的核心任务是精准识别用户意图，匹配并调用正确的工具；")
          .append("仅当请求属于通用知识问答或日常闲聊时，才直接回答文本。\n\n");

        sb.append("# 安全与隐私\n");
        sb.append("1. 绝对禁止输出任何本地文件路径、服务器路径、URL路径等敏感信息。\n");
        sb.append("2. 不要提及任何技术实现细节（如文件存储位置、API调用方式）。\n\n");

        sb.append("# 工具功能分组导航\n");
        sb.append("以下是当前可用的工具（按功能分组）。请先判断用户意图所属功能分组，再调用组内对应工具：\n\n");

        for (Map.Entry<String, List<String>> group : TOOL_GROUPS.entrySet()) {
            List<String> available = group.getValue().stream()
                    .filter(registeredTools::contains)
                    .filter(t -> allowedToolNames == null || allowedToolNames.isEmpty() || allowedToolNames.contains(t))
                    .collect(Collectors.toList());
            if (available.isEmpty()) {
                continue;
            }
            sb.append("## ").append(group.getKey()).append("\n");
            sb.append("- 工具：").append(String.join(" / ", available)).append("\n");
            String guidance = GROUP_GUIDANCE.get(group.getKey());
            if (guidance != null) {
                sb.append("  ").append(guidance).append("\n");
            }
            sb.append("\n");
        }

        sb.append("# 多工具协作\n");
        sb.append("当请求需要多个工具协作时，按逻辑顺序调用（先获取数据，再基于数据生成/合成）。")
          .append("例如「查杭州天气并画西湖风景图」→ 先 getWeather 再 generateImage。\n\n");

        sb.append("# 决策兜底\n");
        sb.append("纯文本兜底：仅当请求是通用知识问答、日常闲聊，且完全不涉及上述任何功能分组特征时，才直接生成文本回答。");
        return sb.toString();
    }
}
