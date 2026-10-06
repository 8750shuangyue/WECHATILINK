package com.example.demo.care.service;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.BaseTool;
import com.example.demo.agent.tools.ToolDefinition;
import com.example.demo.agent.tools.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class NearbyServiceTool extends BaseTool {

    public static final String TOOL_NAME = "searchNearbyService";
    public static final String TOOL_DESCRIPTION = "查找附近宠物医院、急诊、植物医院和园艺店。返回结果末尾有【必须保留的导航链接】段落，你必须原样输出这些URL链接，绝对不能省略、改写或总结它们。";

    private final NearbyServiceSearchService nearbyServiceSearchService;
    private final ToolDefinition toolDefinition;

    public NearbyServiceTool(NearbyServiceSearchService nearbyServiceSearchService) {
        this.nearbyServiceSearchService = nearbyServiceSearchService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("serviceType", "string", "服务类型：hospital(宠物医院)/emergency(24小时急诊)/clinic(诊所)/plant_hospital(植物医院)/gardening(园艺店)/pet_shop(宠物店)/grooming(美容)")
                .parameter("location", "string", "位置，如：北京市海淀区")
                .required("location")
                .build();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public ToolDefinition getDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolResult<String> execute(JSONObject params) {
        String serviceType = params.getString("serviceType");
        String location = params.getString("location");

        if (location == null || location.isBlank()) {
            return ToolResult.failure("请提供具体的位置信息，如：杭州市余杭区文一西路");
        }

        logger.info("Executing searchNearbyService: type={}, location={}", serviceType, location);

        try {
            String result = nearbyServiceSearchService.searchNearbyService(serviceType, location);
            return ToolResult.success(result);
        } catch (Exception e) {
            logger.error("searchNearbyService execution failed", e);
            return ToolResult.failure("附近服务搜索出错：" + e.getMessage());
        }
    }
}