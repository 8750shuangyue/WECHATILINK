package com.example.demo.gallery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
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
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "all") String type,
                                    HttpServletRequest request) {
        String userId = (String) request.getAttribute("userName");
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", mediaAssetService.listByUser(userId, type));
        return r;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userName");
        Map<String, Object> r = new HashMap<>();
        r.put("success", mediaAssetService.delete(userId, id));
        return r;
    }
}
