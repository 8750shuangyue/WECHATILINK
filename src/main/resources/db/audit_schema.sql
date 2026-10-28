CREATE TABLE IF NOT EXISTS operation_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NULL,
    user_id VARCHAR(100) NULL,
    client_ip VARCHAR(64) NULL,
    action VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    target_type VARCHAR(32) NULL,
    target_id VARCHAR(128) NULL,
    detail VARCHAR(1000) NULL,
    request_method VARCHAR(10) NULL,
    request_path VARCHAR(255) NULL,
    user_agent VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_user_action (user_id, action, created_at),
    INDEX idx_audit_action_outcome (action, outcome, created_at),
    INDEX idx_audit_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键操作审计'
