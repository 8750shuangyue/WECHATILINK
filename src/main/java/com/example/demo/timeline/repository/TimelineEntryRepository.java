package com.example.demo.timeline.repository;

import com.example.demo.timeline.entity.TimelineEntry;
import com.example.demo.timeline.entity.TimelineEntry.EntryType;
import com.example.demo.timeline.entity.TimelineEntry.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimelineEntryRepository extends JpaRepository<TimelineEntry, Long> {
    List<TimelineEntry> findByUserIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(String userId, TargetType targetType, Long targetId);
    List<TimelineEntry> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(TargetType targetType, Long targetId);
    List<TimelineEntry> findByUserIdAndTargetTypeAndTargetIdAndEntryType(String userId, TargetType targetType, Long targetId, EntryType entryType);
}
