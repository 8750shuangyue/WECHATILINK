package com.example.demo.chat.repository.mysql;

import com.example.demo.chat.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, String> {
}