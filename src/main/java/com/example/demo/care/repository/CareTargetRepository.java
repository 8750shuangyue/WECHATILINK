package com.example.demo.care.repository;

import com.example.demo.care.model.CareTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CareTargetRepository extends JpaRepository<CareTarget, Long> {
    List<CareTarget> findByUserId(String userId);
    Optional<CareTarget> findByIdAndUserId(Long id, String userId);
    List<CareTarget> findByUserIdAndType(String userId, CareTarget.TargetType type);
}
