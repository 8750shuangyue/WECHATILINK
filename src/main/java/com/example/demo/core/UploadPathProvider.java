package com.example.demo.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 上传目录的唯一入口。所有读写 uploads 的代码都必须经过这里，
 * 避免静态资源目录被重复映射到 /** 而绕过登录校验。
 */
@Component
public class UploadPathProvider {

    private final Path root;
    private final Path tempDir;

    public UploadPathProvider(@Value("${app.upload.dir:./uploads}") String uploadDir,
                              @Value("${app.upload.temp-dir:./tmp}") String tempDir) throws IOException {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.tempDir = Paths.get(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        Files.createDirectories(this.tempDir);
    }

    public Path root() {
        return root;
    }

    public Path tempDir() {
        return tempDir;
    }

    /**
     * 把用户提供的文件名限制在 uploads 根目录内。
     */
    public Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        Path name = Paths.get(fileName).getFileName();
        if (name == null) {
            throw new IllegalArgumentException("文件名无效");
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件路径");
        }
        return target;
    }

    public boolean isWithinRoot(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }

    public String urlOf(String fileName) {
        return "/uploads/" + fileName;
    }
}
