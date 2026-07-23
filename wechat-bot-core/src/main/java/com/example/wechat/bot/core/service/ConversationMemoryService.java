package com.example.wechat.bot.core.service;

import com.example.wechat.bot.core.model.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    /** 每个用户最多保留 20 条消息（≈ 10 轮 user + assistant） */
    private static final int MAX_HISTORY = 20;

    /** 超过 1 小时无消息的用户自动清理 */
    private static final long IDLE_TIMEOUT_MS = 3_600_000;

    private final Map<String, ConversationEntry> memory = new ConcurrentHashMap<>();

    /**
     * 获取指定用户的对话历史（只读，不会修改内部状态）。
     * 如果用户不存在返回空列表。
     */
    public List<ChatMessage> getHistory(String userId) {
        ConversationEntry entry = memory.get(userId);
        if (entry == null) return List.of();
        entry.lastActiveAt = System.currentTimeMillis();
        return entry.history;
    }

    /** 追加用户消息，自动裁剪超量历史 */
    public void addUserMessage(String userId, String content) {
        memory.computeIfAbsent(userId, k -> new ConversationEntry())
                .add(new ChatMessage("user", content));
    }

    /** 追加 AI 回复，自动裁剪超量历史 */
    public void addAssistantMessage(String userId, String content) {
        memory.computeIfAbsent(userId, k -> new ConversationEntry())
                .add(new ChatMessage("assistant", content));
    }

    /** 手动清除指定用户的记忆 */
    public void clear(String userId) {
        memory.remove(userId);
    }

    /**
     * 每 10 分钟清理一次超过 1 小时无活动的用户记忆。
     * 由 @EnableScheduling 触发。
     */
    @Scheduled(fixedRate = 600_000)
    void cleanStaleEntries() {
        long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MS;
        int before = memory.size();
        memory.entrySet().removeIf(e -> e.getValue().lastActiveAt < cutoff);
        int removed = before - memory.size();
        if (removed > 0) {
            log.info("清理了 {} 个过期对话记忆（剩余 {}）", removed, memory.size());
        }
    }

    private static class ConversationEntry {
        final LinkedList<ChatMessage> history = new LinkedList<>();
        volatile long lastActiveAt = System.currentTimeMillis();

        void add(ChatMessage msg) {
            lastActiveAt = System.currentTimeMillis();
            history.add(msg);
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }
    }
}
