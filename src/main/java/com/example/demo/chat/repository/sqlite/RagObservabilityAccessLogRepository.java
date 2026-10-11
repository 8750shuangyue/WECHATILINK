package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.RagObservabilityAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface RagObservabilityAccessLogRepository
        extends JpaRepository<RagObservabilityAccessLog, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RagObservabilityAccessLog log where log.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
