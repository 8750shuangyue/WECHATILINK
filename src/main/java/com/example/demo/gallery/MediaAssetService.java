package com.example.demo.gallery;

import com.example.demo.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 媒体资产：记录 AI 生成图 / TTS 音频 / 上传分析图的归属，供图片中心展示与删除。
 */
@Service
public class MediaAssetService {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetService.class);

    private final JdbcTemplate jdbc;
    private final FileStorageService fileStorageService;

    public MediaAssetService(JdbcTemplate jdbc, FileStorageService fileStorageService) {
        this.jdbc = jdbc;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 记录一个资产；userId 为空（如未登录的后台路径）时跳过，不产生无主数据。
     */
    public void record(String userId, String fileName, String mediaType, String source) {
        if (userId == null || userId.isBlank() || fileName == null || fileName.isBlank()) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO media_assets(user_id, file_name, media_type, source)
                    VALUES(?,?,?,?)
                    """, userId, fileName, mediaType, source);
        } catch (Exception e) {
            log.warn("[Gallery] Failed to record asset {}: {}", fileName, e.getMessage());
        }
    }

    public List<Map<String, Object>> listByUser(String userId, String type) {
        try {
            if (type != null && !type.isBlank() && !"all".equalsIgnoreCase(type)) {
                return jdbc.queryForList("""
                        SELECT id, file_name AS fileName, media_type AS mediaType, source, created_at AS createdAt
                        FROM media_assets
                        WHERE user_id = ? AND media_type = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 200
                        """, userId, type);
            }
            return jdbc.queryForList("""
                    SELECT id, file_name AS fileName, media_type AS mediaType, source, created_at AS createdAt
                    FROM media_assets
                    WHERE user_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT 200
                    """, userId);
        } catch (Exception e) {
            log.warn("[Gallery] List failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除资产：校验归属后删除物理文件与记录。
     */
    public boolean delete(String userId, Long id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT file_name FROM media_assets WHERE id = ? AND user_id = ?", id, userId);
            if (rows.isEmpty()) {
                return false;
            }
            String fileName = String.valueOf(rows.get(0).get("file_name"));
            try {
                fileStorageService.deleteFile(fileName);
            } catch (Exception e) {
                log.warn("[Gallery] File delete failed (row will still be removed): {}", e.getMessage());
            }
            jdbc.update("DELETE FROM media_assets WHERE id = ? AND user_id = ?", id, userId);
            return true;
        } catch (Exception e) {
            log.warn("[Gallery] Delete failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
}
