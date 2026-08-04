package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.CareRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LegacyCareRecordRepository extends JpaRepository<CareRecord, Long> {
    List<CareRecord> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, Long targetId);
    void deleteByTargetTypeAndTargetId(String targetType, Long targetId);
}
