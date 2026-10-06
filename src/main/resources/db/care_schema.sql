-- 护理提醒表
CREATE TABLE IF NOT EXISTS care_reminder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID',
    target_type VARCHAR(20) COMMENT '目标类型：pet/plant',
    target_id BIGINT COMMENT '目标ID',
    reminder_type VARCHAR(50) NOT NULL COMMENT '提醒类型：浇水/施肥/驱虫/疫苗/喂药',
    content TEXT COMMENT '提醒内容',
    metadata JSON COMMENT '扩展字段（药物剂量/疫苗批次等）',
    due_at DATETIME NOT NULL COMMENT '计划时间',
    repeat_rule VARCHAR(20) COMMENT '重复规则：DAILY/WEEKLY/MONTHLY',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态：PENDING/SENT/DONE',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_status_due_at (status, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护理提醒表';

-- 识别历史表（用于图片对比）
CREATE TABLE IF NOT EXISTS identify_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID',
    target_id BIGINT COMMENT '关联护理对象ID',
    identify_type VARCHAR(20) NOT NULL COMMENT '识别类型：plant/pet',
    result TEXT COMMENT '识别结果',
    image_url VARCHAR(500) COMMENT '原图路径',
    metadata JSON COMMENT '扩展字段（置信度/特征等）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_type_time (user_id, identify_type, created_at DESC),
    INDEX idx_target_id (target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='识别历史表';

-- 数据迁移：为已有表添加缺失字段（如已存在则跳过）
-- ALTER TABLE identify_history ADD COLUMN target_id BIGINT COMMENT '关联护理对象ID' AFTER user_id;
-- ALTER TABLE identify_history ADD COLUMN image_url VARCHAR(500) COMMENT '原图路径' AFTER result;
-- ALTER TABLE identify_history ADD COLUMN metadata JSON COMMENT '扩展字段' AFTER image_url;
-- ALTER TABLE care_reminder ADD COLUMN metadata JSON COMMENT '扩展字段' AFTER content;