package com.example.demo.timeline;

import com.example.demo.aicare.Result;
import com.example.demo.timeline.entity.TimelineEntry;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/timeline")
public class TimelineController {

    private static final Logger logger = LoggerFactory.getLogger(TimelineController.class);

    private final TimelineService timelineService;

    public TimelineController(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/{targetType}/{targetId}")
    public Result<List<TimelineEntry>> getTimeline(@PathVariable String targetType,
                                                    @PathVariable Long targetId,
                                                    HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        logger.info("Get timeline for {} / {}", targetType, targetId);
        List<TimelineEntry> entries = timelineService.getTimeline(targetType, targetId);
        return Result.success(entries);
    }

    @PostMapping("/entry")
    public Result<TimelineEntry> createEntry(@RequestBody Map<String, Object> params,
                                              HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        String targetType = (String) params.get("targetType");
        Long targetId = params.get("targetId") != null ? ((Number) params.get("targetId")).longValue() : null;
        String entryType = (String) params.get("entryType");
        String title = (String) params.get("title");
        String description = (String) params.get("description");
        String imageUrl = (String) params.get("imageUrl");

        logger.info("Create timeline entry: type={}, targetType={}, targetId={}", entryType, targetType, targetId);

        TimelineEntry entry;
        switch (entryType.toUpperCase()) {
            case "PHOTO":
                entry = timelineService.addPhotoEntry(userName, targetType, targetId, imageUrl);
                break;
            case "MILESTONE":
                entry = timelineService.addMilestoneEntry(userName, targetType, targetId, title, description);
                break;
            case "CARE":
                entry = timelineService.addCareEntry(userName, targetType, targetId, title, description);
                break;
            default:
                return Result.error("无效的条目类型：" + entryType);
        }

        return Result.success(entry);
    }

    @PostMapping("/entry/{id}/annotate")
    public Result<TimelineEntry> annotateEntry(@PathVariable Long id, HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        logger.info("Annotate timeline entry: {}", id);

        try {
            TimelineEntry entry = timelineService.annotateEntry(id, userName);
            return Result.success(entry);
        } catch (IOException e) {
            logger.error("Annotate entry failed", e);
            return Result.error("AI标注失败：" + e.getMessage());
        }
    }

    @PostMapping("/milestone/auto")
    public Result<String> autoDetectMilestones(@RequestBody Map<String, Object> params,
                                                HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) {
            return Result.error("未登录");
        }

        String targetType = (String) params.get("targetType");
        Long targetId = params.get("targetId") != null ? ((Number) params.get("targetId")).longValue() : null;

        logger.info("Auto-detect milestones for {} / {}", targetType, targetId);

        try {
            timelineService.autoDetectMilestones(userName, targetType, targetId);
            return Result.success("自动检测完成");
        } catch (IOException e) {
            logger.error("Auto-detect milestones failed", e);
            return Result.error("自动检测失败：" + e.getMessage());
        }
    }
}
