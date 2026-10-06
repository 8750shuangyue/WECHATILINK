package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class WebSearchTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TOOL_NAME = "webSearch";
    private static final String TOOL_DESCRIPTION = "联网搜索工具，获取实时新闻、天气、股价、最新政策等时效性信息";

    @Value("${baidu.search.api-key}")
    private String apiKey;

    @Value("${baidu.search.api-url:https://api.baidu.com/json/snc/v1/search}")
    private String apiUrl;

    private final OkHttpClient httpClient;
    private final ToolDefinition toolDefinition;

    public WebSearchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();

        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("query", "string", "搜索关键词")
                .required("query")
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
        String query = params.getString("query");

        if (query == null || query.trim().isEmpty()) {
            return ToolResult.failure("请提供搜索关键词");
        }

        logger.info("[BaiduSearch] Executing search for query: {}", query);

        try {
            int count = params.getIntValue("count");
            if (count <= 0) count = 10;
            if (count > 50) count = 50;

            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("query", query);
            bodyMap.put("count", count);

            String jsonBody = JSON.toJSONString(bodyMap);

            String maskedKey = (apiKey != null && apiKey.length() > 8)
                    ? apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4)
                    : "null";
            logger.info("[BaiduSearch] API call - url: {}, apiKey: {}, query: {}, count: {}", apiUrl, maskedKey, query, count);

            RequestBody requestBody = RequestBody.create(
                    jsonBody,
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("x-api-key", apiKey != null ? apiKey : "")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            logger.info("[BaiduSearch] Sending POST request to: {}, body: {}", apiUrl, jsonBody);

            try (Response response = httpClient.newCall(request).execute()) {
                int statusCode = response.code();
                logger.info("[BaiduSearch] Response status: {}", statusCode);

                if (!response.isSuccessful()) {
                    String errorBody = "";
                    if (response.body() != null) {
                        try {
                            errorBody = response.body().string();
                        } catch (Exception e) {
                            logger.warn("[BaiduSearch] Failed to read error response body", e);
                        }
                    }
                    logger.error("[BaiduSearch] Request failed - HTTP Code: {}, Error Body: {}", statusCode, errorBody);

                    String errorMsg;
                    if (statusCode == 401) {
                        errorMsg = "百度搜索服务认证失败，请检查 API Key 是否有效";
                    } else if (statusCode == 429) {
                        errorMsg = "搜索请求过于频繁，请稍后再试";
                    } else if (statusCode == 403) {
                        errorMsg = "百度搜索服务无权限，请检查 API Key 是否有搜索权限";
                    } else if (statusCode == 400) {
                        errorMsg = "百度搜索请求参数错误 (HTTP 400)，详情: " + errorBody;
                    } else {
                        errorMsg = "百度搜索服务连接失败 (HTTP " + statusCode + ")";
                    }
                    return ToolResult.failure(errorMsg);
                }

                String responseStr = response.body().string();
                logger.info("[BaiduSearch] Response received, length: {} chars", responseStr.length());
                logger.debug("[BaiduSearch] Full response: {}", responseStr);

                JSONObject json = JSON.parseObject(responseStr);
                String formattedResult = formatResult(json, query);

                logger.info("[BaiduSearch] Search completed successfully, result length: {} chars", formattedResult.length());
                return ToolResult.success(formattedResult);
            }

        } catch (IOException e) {
            logger.error("[BaiduSearch] Network request exception", e);
            return ToolResult.failure("百度搜索网络超时，请稍后再试");
        } catch (Exception e) {
            logger.error("[BaiduSearch] Parsing exception", e);
            return ToolResult.failure("搜索数据解析失败，请联系管理员");
        }
    }

    private String formatResult(JSONObject json, String query) {
        StringBuilder sb = new StringBuilder();

        String status = json.getString("status");
        if ("error".equals(status) || "fail".equals(status)) {
            String message = json.getString("message");
            logger.warn("[BaiduSearch] API returned error status: {}, message: {}", status, message);
            return "搜索服务返回错误：" + (message != null ? message : "未知错误");
        }

        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            Object rawResults = json.get("results");
            if (rawResults instanceof JSONArray) {
                return formatResultsArray((JSONArray) rawResults, query, sb);
            }
            return "🔍 搜索「" + (query != null ? query : "") + "」未返回有效数据。";
        }

        String total = data.getString("total");
        JSONArray list = data.getJSONArray("list");

        if (list == null || list.isEmpty()) {
            return "🔍 没有找到与「" + (query != null ? query : "") + "」相关的结果。";
        }

        sb.append("📎 **百度搜索结果**");
        if (total != null && !total.isBlank()) {
            sb.append("（共").append(total).append("条）");
        }
        sb.append("：\n\n");

        int count = 0;
        for (int i = 0; i < list.size() && count < 5; i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("title");
            String url = item.getString("url");
            String abstractText = item.getString("abstract");
            String site = item.getString("site");
            String time = item.getString("time");

            if (title == null || title.isBlank()) continue;

            count++;
            sb.append(count).append(". ");
            sb.append(title).append("\n");

            if (abstractText != null && !abstractText.isBlank()) {
                sb.append("   ").append(abstractText).append("\n");
            }

            if (url != null && !url.isBlank()) {
                sb.append("   🔗 ").append(url).append("\n");
            }

            if (site != null || time != null) {
                sb.append("   ");
                if (site != null && !site.isBlank()) {
                    sb.append("来源：").append(site);
                }
                if (time != null && !time.isBlank()) {
                    if (site != null && !site.isBlank()) sb.append(" | ");
                    sb.append("时间：").append(time);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().isEmpty()
                ? "🔍 没有找到与「" + (query != null ? query : "") + "」相关的内容。"
                : sb.toString();
    }

    private String formatResultsArray(JSONArray results, String query, StringBuilder sb) {
        sb.append("📎 **百度搜索结果**：\n\n");
        int count = 0;
        for (int i = 0; i < results.size() && count < 5; i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("title");
            String url = item.getString("url");
            String snippet = item.getString("snippet");
            String abstractText = item.getString("abstract");

            if (title == null || title.isBlank()) continue;

            count++;
            sb.append(count).append(". ");
            sb.append(title).append("\n");

            String desc = abstractText != null ? abstractText : snippet;
            if (desc != null && !desc.isBlank()) {
                sb.append("   ").append(desc).append("\n");
            }

            if (url != null && !url.isBlank()) {
                sb.append("   🔗 ").append(url).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().isEmpty()
                ? "🔍 没有找到与「" + (query != null ? query : "") + "」相关的内容。"
                : sb.toString();
    }

    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}