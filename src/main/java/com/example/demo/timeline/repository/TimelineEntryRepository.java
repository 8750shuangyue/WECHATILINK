package com.example.demo.timeline.repository;

import com.example.demo.timeline.entity.TimelineEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, Long> {
    List<TimelineEntry> findByUserIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(String userId, String targetType, Long targetId);
    List<TimelineEntry> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, Long targetId);
    List<TimelineEntry> findByUserIdAndTargetTypeAndTargetIdAndEntryType(String userId, String targetType, Long targetId, String entryType);
}
