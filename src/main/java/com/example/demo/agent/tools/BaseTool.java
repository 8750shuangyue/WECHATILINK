package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseTool {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    public abstract String getName();

    public abstract String getDescription();

    public abstract ToolDefinition getDefinition();

    public abstract ToolResult<?> execute(JSONObject params);

    public String getSchemaJson() {
        return getDefinition().toJson();
    }

    protected ToolResult<?> safeExecute(JSONObject params) {
        try {
            return execute(params);
        } catch (IllegalArgumentException e) {
            logger.warn("Tool {} received invalid arguments: {}", getName(), e.getMessage());
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            logger.error("Tool {} execution failed", getName(), e);
            return ToolResult.failure("工具执行失败，请重试。");
        }
    }
}