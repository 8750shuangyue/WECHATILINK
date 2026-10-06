-- 工具调用日志表（数据观测 / 统计面板使用）
CREATE TABLE IF NOT EXISTS tool_call_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) COMMENT '发起调用的用户ID',
    tool_name VARCHAR(100) NOT NULL COMMENT '工具名',
    success TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否成功：1成功/0失败',
    duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时（毫秒）',
    error_msg VARCHAR(500) COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    INDEX idx_tool_name (tool_name),
    INDEX idx_created_at (created_at),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用日志';
