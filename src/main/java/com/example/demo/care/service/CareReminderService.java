package com.example.demo.care.service;

import com.example.demo.care.model.CareRecord;
import com.example.demo.care.model.CareTarget;
import com.example.demo.care.repository.CareRecordRepository;
import com.example.demo.care.repository.CareTargetRepository;
import com.example.demo.core.ILinkMessageListener;
import com.example.demo.push.WebPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class CareReminderService {

    private static final Logger log = LoggerFactory.getLogger(CareReminderService.class);

    private final JdbcTemplate jdbc;
    private final CareTargetRepository careTargetRepository;
    private final CareRecordRepository careRecordRepository;
    private final WebPushService webPushService;
    private final ILinkMessageListener wechatSender;

    public CareReminderService(JdbcTemplate jdbc,
                               CareTargetRepository careTargetRepository,
                               CareRecordRepository careRecordRepository,
                               WebPushService webPushService,
                               ILinkMessageListener wechatSender) {
        this.jdbc = jdbc;
        this.careTargetRepository = careTargetRepository;
        this.careRecordRepository = careRecordRepository;
        this.webPushService = webPushService;
        this.wechatSender = wechatSender;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void sendDueReminders() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, user_id, target_type, target_id, reminder_type, content, due_at, repeat_rule
                    FROM care_reminder
                    WHERE status='PENDING' AND due_at <= NOW()
                    ORDER BY due_at LIMIT 20
                    """);
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                String userId = String.valueOf(row.get("user_id"));
                String reminderText = buildReminderText(row);

                log.info("[Reminder] Sending reminder to user {}: {}", userId, reminderText);

                deliverReminder(userId, reminderText);

                jdbc.update("UPDATE care_reminder SET status='SENT' WHERE id=? AND status='PENDING'", id);
            }
        } catch (Exception e) {
            log.error("[Reminder] Error sending reminders: {}", e.getMessage(), e);
        }
    }

    /**
     * 把提醒真正送达用户：
     * - 微信 ILink 用户（user_id 形如 xxx@im.wechat）→ 直接发微信消息
     * - 网页用户 → WebPush 浏览器推送
     */
    private void deliverReminder(String userId, String reminderText) {
        try {
            if (userId != null && userId.contains("@im.wechat")) {
                wechatSender.sendTextToUser(userId, reminderText);
            } else {
                webPushService.sendPushToUser(userId, "🌿 护理提醒", reminderText, "/home.html");
            }
        } catch (Exception e) {
            log.error("[Reminder] Failed to deliver reminder to user {}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 统计用户当前待办（PENDING）提醒数量，供首页数据卡使用。
     */
    public int countPendingReminders(String userId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM care_reminder WHERE user_id=? AND status='PENDING'",
                    Integer.class, userId);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.error("[Reminder] Failed to count pending reminders for user {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    private String buildReminderText(Map<String, Object> row) {
        String reminderType = String.valueOf(row.get("reminder_type"));
        String content = String.valueOf(row.get("content"));
        Object dueAt = row.get("due_at");

        StringBuilder sb = new StringBuilder();
        sb.append("⏰ 护理提醒\n");
        sb.append(reminderType).append("：").append(content).append("\n");
        if (dueAt != null) {
            sb.append("计划时间：").append(dueAt).append("\n");
        }
        sb.append("完成后回复「已完成」");
        return sb.toString();
    }

    public String createReminder(String userId, String targetType, Long targetId,
                                 String reminderType, String content, String dueAt, String repeatRule) {
        try {
            LocalDateTime due = LocalDateTime.parse(dueAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            jdbc.update("""
                    INSERT INTO care_reminder(user_id, target_type, target_id, reminder_type, content, due_at, repeat_rule, status)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, userId, targetType, targetId, reminderType, content, due, emptyToNull(repeatRule), "PENDING");

            return "提醒已创建：" + dueAt + "，" + content +
                    (repeatRule != null && !repeatRule.isBlank() ? "（重复规则：" + repeatRule + "）" : "");
        } catch (Exception e) {
            log.error("[Reminder] Error creating reminder: {}", e.getMessage(), e);
            return "创建提醒失败：" + e.getMessage();
        }
    }

    public String completeLatestReminder(String userId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT * FROM care_reminder WHERE user_id=? AND status='SENT'
                    ORDER BY due_at DESC LIMIT 1
                    """, userId);

            if (rows.isEmpty()) {
                return "没有等待确认的提醒。";
            }

            Map<String, Object> row = rows.get(0);
            long id = ((Number) row.get("id")).longValue();
            jdbc.update("UPDATE care_reminder SET status='DONE', completed_at=NOW() WHERE id=?", id);

            String targetType = String.valueOf(row.get("target_type"));
            Long targetId = row.get("target_id") != null ? ((Number) row.get("target_id")).longValue() : null;
            String reminderType = String.valueOf(row.get("reminder_type"));
            String content = String.valueOf(row.get("content"));

            if (targetId != null) {
                CareRecord record = new CareRecord();
                record.setUserId(userId);
                record.setTargetType(CareRecord.TargetType.valueOf(targetType.toUpperCase()));
                record.setTargetId(targetId);
                record.setRecordType(CareRecord.RecordType.CARE);
                record.setContent("按提醒完成：" + content);
                careRecordRepository.save(record);
            }

            String repeatRule = row.get("repeat_rule") == null ? "" : String.valueOf(row.get("repeat_rule"));
            if (!repeatRule.isBlank()) {
                LocalDateTime previous = ((java.sql.Timestamp) row.get("due_at")).toLocalDateTime();
                LocalDateTime next = calculateNextReminder(previous, repeatRule);
                if (next != null) {
                    jdbc.update("""
                            INSERT INTO care_reminder(user_id, target_type, target_id, reminder_type, content, due_at, repeat_rule, status)
                            VALUES(?,?,?,?,?,?,?,?)
                            """, userId, targetType, targetId, reminderType, content, next, repeatRule, "PENDING");
                }
            }

            return "提醒已完成并写入护理记录。" + (repeatRule.isBlank() ? "" : "下一次重复提醒已创建。");
        } catch (Exception e) {
            log.error("[Reminder] Error completing reminder: {}", e.getMessage(), e);
            return "完成提醒失败：" + e.getMessage();
        }
    }

    private LocalDateTime calculateNextReminder(LocalDateTime previous, String repeatRule) {
        if ("DAILY".equalsIgnoreCase(repeatRule)) {
            return previous.plusDays(1);
        } else if ("WEEKLY".equalsIgnoreCase(repeatRule)) {
            return previous.plusWeeks(1);
        } else if ("MONTHLY".equalsIgnoreCase(repeatRule)) {
            return previous.plusMonths(1);
        }
        return null;
    }

    public List<Map<String, Object>> getUserReminders(String userId) {
        return jdbc.queryForList("""
                SELECT id, target_type, target_id, reminder_type, content, due_at, status, repeat_rule
                FROM care_reminder WHERE user_id=? ORDER BY due_at DESC LIMIT 20
                """, userId);
    }

    public List<Map<String, Object>> getPendingReminders(String userId) {
        return jdbc.queryForList("""
                SELECT id, target_type, target_id, reminder_type, content, due_at, status, repeat_rule
                FROM care_reminder WHERE user_id=? AND status IN ('PENDING', 'SENT')
                ORDER BY due_at DESC LIMIT 20
                """, userId);
    }

    public CareRecord createReminder(String userId, Long targetId, String title, 
                                     String content, LocalDateTime reminderTime) {
        try {
            jdbc.update("""
                    INSERT INTO care_reminder(user_id, target_type, target_id, reminder_type, content, due_at, status)
                    VALUES(?,?,?,?,?,?,?)
                    """, userId, "PET", targetId, title, content, reminderTime, "PENDING");

            CareRecord record = new CareRecord();
            record.setUserId(userId);
            record.setTargetType(CareRecord.TargetType.PET);
            record.setTargetId(targetId);
            record.setRecordType(CareRecord.RecordType.REMINDER);
            record.setTitle(title);
            record.setContent(content);
            record.setReminderTime(reminderTime);
            record.setIsCompleted(false);
            return record;
        } catch (Exception e) {
            log.error("[Reminder] Error creating reminder: {}", e.getMessage(), e);
            throw new RuntimeException("创建提醒失败：" + e.getMessage(), e);
        }
    }

    public void completeReminder(Long id, String userId) {
        try {
            jdbc.update("UPDATE care_reminder SET status='DONE', completed_at=NOW() WHERE id=? AND user_id=?",
                    id, userId);
        } catch (Exception e) {
            log.error("[Reminder] Error completing reminder: {}", e.getMessage(), e);
            throw new RuntimeException("完成提醒失败：" + e.getMessage(), e);
        }
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
