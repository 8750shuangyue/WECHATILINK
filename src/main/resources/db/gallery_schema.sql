-- 媒体资产表（图片中心：AI 生成图 / TTS 音频 / 上传分析图）
CREATE TABLE IF NOT EXISTS media_assets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL COMMENT '归属用户',
    file_name VARCHAR(255) NOT NULL COMMENT 'uploads 下的文件名',
    media_type VARCHAR(20) NOT NULL DEFAULT 'image' COMMENT 'image/audio',
    source VARCHAR(30) NOT NULL DEFAULT 'generated' COMMENT 'generated/uploaded/tts',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_type (user_id, media_type),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体资产';
