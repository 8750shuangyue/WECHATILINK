package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.RagObservabilityAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagObservabilityAccessLogRepository
        extends JpaRepository<RagObservabilityAccessLog, Long> {
}
