package com.example.demo.care.service;

import com.example.demo.care.model.CareRecord;
import com.example.demo.care.model.CareTarget;
import com.example.demo.care.model.IdentifyHistory;
import com.example.demo.care.repository.CareRecordRepository;
import com.example.demo.care.repository.CareTargetRepository;
import com.example.demo.care.repository.IdentifyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareRecordService {

    private final CareTargetRepository careTargetRepository;
    private final CareRecordRepository careRecordRepository;
    private final IdentifyHistoryRepository identifyHistoryRepository;
    private final CareReminderService careReminderService;

    @Transactional
    public CareTarget createTarget(CareTarget target) {
        log.info("Creating care target: userId={}, name={}, type={}", 
                target.getUserId(), target.getName(), target.getType());
        return careTargetRepository.save(target);
    }

    @Transactional
    public CareTarget updateTarget(Long id, String userId, CareTarget update) {
        CareTarget target = careTargetRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("护理对象不存在"));
        
        if (update.getName() != null) target.setName(update.getName());
        if (update.getSpecies() != null) target.setSpecies(update.getSpecies());
        if (update.getBreed() != null) target.setBreed(update.getBreed());
        if (update.getAvatarUrl() != null) target.setAvatarUrl(update.getAvatarUrl());
        if (update.getHealthStatus() != null) target.setHealthStatus(update.getHealthStatus());
        if (update.getDescription() != null) target.setDescription(update.getDescription());
        if (update.getMetadata() != null) target.setMetadata(update.getMetadata());
        
        log.info("Updating care target: id={}, userId={}", id, userId);
        return careTargetRepository.save(target);
    }

    public Optional<CareTarget> getTarget(Long id, String userId) {
        return careTargetRepository.findByIdAndUserId(id, userId);
    }

    public List<CareTarget> getTargetsByUser(String userId) {
        return careTargetRepository.findByUserId(userId);
    }

    public List<CareTarget> getTargetsByType(String userId, CareTarget.TargetType type) {
        return careTargetRepository.findByUserIdAndType(userId, type);
    }

    @Transactional
    public void deleteTarget(Long id, String userId) {
        CareTarget target = careTargetRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("护理对象不存在"));
        careTargetRepository.delete(target);
        log.info("Deleted care target: id={}, userId={}", id, userId);
    }

    @Transactional
    public CareRecord createRecord(CareRecord record) {
        log.info("Creating care record: userId={}, targetId={}, type={}", 
                record.getUserId(), record.getTargetId(), record.getRecordType());
        return careRecordRepository.save(record);
    }

    public List<CareRecord> getRecordsByUser(String userId) {
        return careRecordRepository.findByUserId(userId);
    }

    public List<CareRecord> getRecordsByTarget(String userId, Long targetId) {
        return careRecordRepository.findByUserIdAndTargetId(userId, targetId);
    }

    public List<CareRecord> getRecordsByType(String userId, CareRecord.RecordType recordType) {
        return careRecordRepository.findByUserIdAndRecordType(userId, recordType);
    }

    @Transactional
    public CareRecord saveMedication(String userId, String targetType, Long targetId,
                                     String medicine, String instruction, String prescribedBy) {
        log.info("[Audit] saveMedication: userId={}, targetId={}, medicine={}", userId, targetId, medicine);
        if (targetId == null) {
            throw new IllegalArgumentException("请先选择具体档案，再保存用药记录");
        }

        CareRecord record = new CareRecord();
        record.setUserId(userId);
        record.setTargetType(CareRecord.TargetType.valueOf(targetType.toUpperCase()));
        record.setTargetId(targetId);
        record.setRecordType(CareRecord.RecordType.MEDICATION);
        record.setContent(medicine + "；用法：" + instruction + "；处方来源：" + prescribedBy);
        return careRecordRepository.save(record);
    }

    @Transactional
    public IdentifyHistory saveIdentifyHistory(String userId, Long targetId, String identifyType,
                                                String result, String imageUrl, String metadata) {
        log.info("[Audit] saveIdentifyHistory: userId={}, targetId={}, type={}", userId, targetId, identifyType);
        IdentifyHistory history = IdentifyHistory.builder()
                .userId(userId)
                .targetId(targetId)
                .identifyType(identifyType)
                .result(result)
                .imageUrl(imageUrl)
                .metadata(metadata)
                .build();
        return identifyHistoryRepository.save(history);
    }

    public String compareImages(String userId, String type) {
        log.info("[Audit] compareImages: userId={}, type={}", userId, type);
        List<IdentifyHistory> histories = identifyHistoryRepository
                .findByUserIdAndIdentifyTypeOrderByCreatedAtDesc(userId, type);

        if (histories.size() < 2) {
            return "至少需要上传并识别两张不同时间的图片才能对比。当前已上传 " + histories.size() + " 张。";
        }

        IdentifyHistory latest = histories.get(0);
        IdentifyHistory previous = histories.get(1);

        StringBuilder sb = new StringBuilder();
        sb.append("【图片变化对比报告】\n\n");
        sb.append("📸 最新识别：\n").append(latest.getResult() != null ? latest.getResult() : "无记录").append("\n\n");
        sb.append("📸 上一次识别：\n").append(previous.getResult() != null ? previous.getResult() : "无记录").append("\n\n");
        sb.append("请比较两次结果中的外观、健康状态、叶色/毛发、病斑或体态变化。");

        return sb.toString();
    }

    @Transactional
    public CareRecord generateCarePlan(String userId, String targetType, Long targetId,
                                        String breed, String age, String season, String weather) {
        log.info("[Audit] generateCarePlan: userId={}, targetId={}, breed={}", userId, targetId, breed);
        StringBuilder plan = new StringBuilder();
        plan.append("【智能护理计划】\n");
        if (breed != null) plan.append("品种：").append(breed).append("\n");
        if (age != null) plan.append("年龄：").append(age).append("\n");
        if (season != null) plan.append("季节：").append(season).append("\n");
        if (weather != null) plan.append("天气：").append(weather).append("\n");

        plan.append("\n根据以上条件，建议的护理周计划：\n");
        plan.append("周一：基础护理（喂食、清洁）\n");
        plan.append("周三：健康检查、适度运动\n");
        plan.append("周五：深度护理、环境消毒\n");
        plan.append("周日：自由活动、行为观察\n");

        CareRecord record = new CareRecord();
        record.setUserId(userId);
        record.setTargetType(targetId != null ? CareRecord.TargetType.valueOf(targetType.toUpperCase()) : CareRecord.TargetType.PET);
        record.setTargetId(targetId);
        record.setRecordType(CareRecord.RecordType.CARE);
        record.setTitle("护理计划");
        record.setContent(plan.toString());
        return careRecordRepository.save(record);
    }

    public String checkMedicationReminders(String userId) {
        log.info("[Audit] checkMedicationReminders: userId={}", userId);
        List<CareRecord> medications = careRecordRepository
                .findByUserIdAndRecordType(userId, CareRecord.RecordType.MEDICATION);

        if (medications.isEmpty()) {
            return "暂无用药记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【用药记录检查】\n");
        for (CareRecord med : medications) {
            sb.append("- ").append(med.getContent()).append("\n");
        }
        return sb.toString();
    }
}
