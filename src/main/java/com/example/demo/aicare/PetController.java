package com.example.demo.aicare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/pet")
public class PetController {

    private static final Logger logger = LoggerFactory.getLogger(PetController.class);

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @PostMapping("/recognize")
    public Result<Map<String, Object>> recognize(@RequestParam("file") MultipartFile file) {
        try {
            logger.info("Pet recognize request, filename: {}, size: {}", 
                    file.getOriginalFilename(), file.getSize());
            Map<String, Object> result = petService.recognizePet(file);
            return Result.success(result);
        } catch (IOException e) {
            logger.error("Pet recognition failed", e);
            return Result.error("宠物识别失败：" + e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<Object> list() {
        try {
            return Result.success(petService.listPets());
        } catch (Exception e) {
            logger.error("Pet list failed", e);
            return Result.error("获取宠物列表失败：" + e.getMessage());
        }
    }
}