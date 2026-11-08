package com.example.demo.gallery;

import com.example.demo.aicare.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图片中心：按当前用户列出/删除 AI 生成图、TTS 音频与上传分析图。
 */
@RestController
@RequestMapping("/api/gallery")
public class GalleryController {

    private final MediaAssetService mediaAssetService;

    public GalleryController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false, defaultValue = "all") String type,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userName");
        return Result.success(mediaAssetService.listByUser(userId, type));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<String>> delete(@PathVariable Long id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userName");
        if (!mediaAssetService.delete(userId, id)) {
            return ResponseEntity.status(404).body(Result.error(404, "素材不存在或无权删除"));
        }
        return ResponseEntity.ok(Result.success("已删除"));
    }
}
