package com.example.demo.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${server.port:8080}")
    private String serverPort;

    private Path uploadPath;

    @PostConstruct
    public void init() throws IOException {
        uploadPath = Paths.get("./uploads");
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
    }

    public String storeImage(byte[] imageBytes, String extension) throws IOException {
        String filename = UUID.randomUUID().toString() + (extension != null ? extension : ".jpg");
        Path targetPath = uploadPath.resolve(filename);
        Files.write(targetPath, imageBytes);
        return "http://localhost:" + serverPort + "/uploads/" + filename;
    }

    public void deleteFile(String fileName) throws IOException {
        Path path = uploadPath.resolve(fileName);
        Files.deleteIfExists(path);
    }
}