package com.example.demo.care.repository;

import com.example.demo.care.model.IdentifyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IdentifyHistoryRepository extends JpaRepository<IdentifyHistory, Long> {

    List<IdentifyHistory> findByUserIdAndIdentifyTypeOrderByCreatedAtDesc(String userId, String identifyType);

    List<IdentifyHistory> findByUserIdAndTargetIdOrderByCreatedAtDesc(String userId, Long targetId);

    List<IdentifyHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    long countByUserIdAndIdentifyType(String userId, String identifyType);
}