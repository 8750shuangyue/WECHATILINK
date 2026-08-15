package com.example.demo.aicare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/plant")
public class PlantController {

    private static final Logger logger = LoggerFactory.getLogger(PlantController.class);

    private final PlantService plantService;

    public PlantController(PlantService plantService) {
        this.plantService = plantService;
    }

    @PostMapping("/recognize")
    public Result<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file, HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) return Result.error("未登录");
        try {
            logger.info("Plant recognize request, filename: {}, size: {}", 
                    file.getOriginalFilename(), file.getSize());
            Map<String, Object> result = plantService.recognizePlant(file, userName);
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Plant recognition failed", e);
            return Result.error("植物识别失败：" + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<Object> list(HttpSession session) {
        String userName = (String) session.getAttribute("user");
        if (userName == null) return Result.error("未登录");
        try {
            return Result.success(plantService.listPlants(userName));
        } catch (Exception e) {
            logger.error("Plant list failed", e);
            return Result.error("获取植物列表失败：" + e.getMessage());
        }
    }
}
