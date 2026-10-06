package com.example.demo.timeline;

import com.example.demo.chat.LlmService;
import com.example.demo.timeline.entity.TimelineEntry;
import com.example.demo.timeline.entity.TimelineEntry.EntryType;
import com.example.demo.timeline.entity.TimelineEntry.TargetType;
import com.example.demo.timeline.repository.TimelineEntryRepository;
import com.example.demo.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class TimelineService {

    private static final Logger logger = LoggerFactory.getLogger(TimelineService.class);

    private final TimelineEntryRepository timelineEntryRepository;
    private final LlmService llmService;
    private final VisionService visionService;

    public TimelineService(TimelineEntryRepository timelineEntryRepository,
                           LlmService llmService,
                           VisionService visionService) {
        this.timelineEntryRepository = timelineEntryRepository;
        this.llmService = llmService;
        this.visionService = visionService;
    }

    public TimelineEntry addPhotoEntry(String userId, String targetType, Long targetId, String imageUrl) {
        TimelineEntry entry = TimelineEntry.builder()
                .userId(userId)
                .targetType(TargetType.valueOf(targetType.toUpperCase()))
                .targetId(targetId)
                .entryType(EntryType.PHOTO)
                .imageUrl(imageUrl)
                .build();
        return timelineEntryRepository.save(entry);
    }

    public TimelineEntry addMilestoneEntry(String userId, String targetType, Long targetId, String title, String description) {
        TimelineEntry entry = TimelineEntry.builder()
                .userId(userId)
                .targetType(TargetType.valueOf(targetType.toUpperCase()))
                .targetId(targetId)
                .entryType(EntryType.MILESTONE)
                .title(title)
                .description(description)
                .build();
        return timelineEntryRepository.save(entry);
    }

    public TimelineEntry addCareEntry(String userId, String targetType, Long targetId, String title, String description) {
        TimelineEntry entry = TimelineEntry.builder()
                .userId(userId)
                .targetType(TargetType.valueOf(targetType.toUpperCase()))
                .targetId(targetId)
                .entryType(EntryType.CARE)
                .title(title)
                .description(description)
                .build();
        return timelineEntryRepository.save(entry);
    }

    public TimelineEntry annotateEntry(Long entryId, String userId) throws IOException {
        TimelineEntry entry = timelineEntryRepository.findById(entryId)
                .orElseThrow(() -> new RuntimeException("Timeline entry not found: " + entryId));

        String targetLabel = entry.getTargetType() == TargetType.PET ? "宠物" : "植物";
        String existingInfo = "";
        if (entry.getTitle() != null) {
            existingInfo += "标题: " + entry.getTitle() + "\n";
        }
        if (entry.getDescription() != null) {
            existingInfo += "描述: " + entry.getDescription() + "\n";
        }
        if (entry.getImageUrl() != null) {
            existingInfo += "图片URL: " + entry.getImageUrl() + "\n";
        }

        String prompt = "请分析以下" + targetLabel + "的成长记录，描述这张照片代表了什么重要的变化或里程碑。" +
                "请用中文回答，简洁专业，2-4句话即可。\n\n" + existingInfo;

        String systemPrompt = "你是一位专业的" + targetLabel + "成长记录分析专家。请帮助识别和描述" + targetLabel + "成长过程中的重要里程碑。";

        String aiDescription = llmService.chat(prompt, systemPrompt);

        entry.setDescription(aiDescription);
        entry.setTitle(entry.getTitle() != null ? "[AI标注] " + entry.getTitle() : "[AI标注] 成长里程碑");
        entry.setEntryType(EntryType.ANNOTATION);

        logger.info("Entry {} annotated by AI for user {}", entryId, userId);
        return timelineEntryRepository.save(entry);
    }

    public List<TimelineEntry> getTimeline(String targetType, Long targetId) {
        return timelineEntryRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId);
    }

    public List<TimelineEntry> getUserTimeline(String userId, String targetType, Long targetId) {
        return timelineEntryRepository.findByUserIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(userId, targetType, targetId);
    }

    public void autoDetectMilestones(String userId, String targetType, Long targetId) throws IOException {
        List<TimelineEntry> photoEntries = timelineEntryRepository
                .findByUserIdAndTargetTypeAndTargetIdAndEntryType(userId, targetType, targetId, "PHOTO");

        if (photoEntries.size() < 2) {
            logger.info("Not enough PHOTO entries to auto-detect milestones for user {} target {}/{}",
                    userId, targetType, targetId);
            return;
        }

        TimelineEntry latest = photoEntries.get(0);
        TimelineEntry previous = photoEntries.get(1);

        if (latest.getImageUrl() == null || previous.getImageUrl() == null) {
            logger.warn("Photo entries missing image URLs, cannot compare");
            return;
        }

        String targetLabel = targetType.equals("PET") ? "宠物" : "植物";

        String prompt = "这是一组" + targetLabel + "的成长对比照片。请比较这两张照片中的变化。" +
                "如果发现明显的变化（如体型变大、叶色变化、新特征出现等），请说明这些变化代表了什么重要的成长里程碑。" +
                "如果没有明显变化，请直接说'无明显变化'。请用中文回答，2-4句话。";

        // For comparison, we describe both images
        String analysis = visionService.analyzeImageWithCustomPrompt(latest.getImageUrl(), prompt);

        if (analysis != null && !analysis.contains("无明显变化")) {
            TimelineEntry milestone = TimelineEntry.builder()
                    .userId(userId)
                    .targetType(TargetType.valueOf(targetType.toUpperCase()))
                    .targetId(targetId)
                    .entryType(EntryType.MILESTONE)
                    .title("[自动检测] 成长里程碑")
                    .description(analysis)
                    .build();
            timelineEntryRepository.save(milestone);
            logger.info("Auto-detected milestone for user {} target {}/{}: {}", userId, targetType, targetId, analysis);
        } else {
            logger.info("No significant changes detected for user {} target {}/{}", userId, targetType, targetId);
        }
    }
}
