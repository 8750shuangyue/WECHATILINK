package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 助手消息来源快照的编解码。
 *
 * <p>存储格式（messages.sources_json）：</p>
 * <pre>
 * {"v":1,"traceId":"...","sources":[{"sourceId":"...","title":"...","rank":1}]}
 * </pre>
 *
 * <p>列表顺序即 rank；写入时显式记录 rank，读取时按顺序重新编号，
 * 防止前端过滤/排序后错位。空来源不落库（返回 null）。</p>
 */
public final class SourceRefCodec {

    private static final Logger logger = LoggerFactory.getLogger(SourceRefCodec.class);

    static final int MAX_SOURCES = 50;
    static final int MAX_TITLE_LENGTH = 300;

    private SourceRefCodec() {
    }

    public static String toJson(List<RagContextService.SourceRef> sources, String traceId) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        JSONArray items = new JSONArray();
        int rank = 0;
        for (RagContextService.SourceRef source : sources) {
            if (source == null || source.sourceId() == null || source.sourceId().isBlank()) {
                continue;
            }
            if (rank >= MAX_SOURCES) {
                break;
            }
            rank++;
            JSONObject item = new JSONObject();
            item.put("sourceId", source.sourceId().trim());
            item.put("title", truncate(source.title(), MAX_TITLE_LENGTH));
            item.put("rank", rank);
            items.add(item);
        }
        if (items.isEmpty()) {
            return null;
        }

        JSONObject root = new JSONObject();
        root.put("v", 1);
        if (traceId != null && !traceId.isBlank()) {
            root.put("traceId", traceId.trim());
        }
        root.put("sources", items);
        return root.toJSONString();
    }

    /**
     * 容错读取：兼容 NULL、坏 JSON、裸数组（无信封）、缺 rank/title。
     * 任何异常都返回空列表，避免历史接口因单条脏数据失败。
     */
    public static List<RagContextService.SourceRef> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = JSON.parse(json);
            JSONArray array = null;
            if (parsed instanceof JSONObject object) {
                array = object.getJSONArray("sources");
            } else if (parsed instanceof JSONArray rawArray) {
                array = rawArray;
            }
            if (array == null || array.isEmpty()) {
                return List.of();
            }

            List<RagContextService.SourceRef> sources = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.size() && sources.size() < MAX_SOURCES; i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String sourceId = item.getString("sourceId");
                if (sourceId == null || sourceId.isBlank()) {
                    continue;
                }
                sourceId = sourceId.trim();
                if (!seen.add(sourceId)) {
                    continue;
                }
                String title = item.getString("title");
                if (title == null || title.isBlank()) {
                    title = sourceId;
                }
                sources.add(new RagContextService.SourceRef(
                        sourceId, truncate(title.trim(), MAX_TITLE_LENGTH), sources.size() + 1));
            }
            return List.copyOf(sources);
        } catch (Exception e) {
            logger.warn("Failed to parse sources_json: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength - 1) + "…";
    }
}
