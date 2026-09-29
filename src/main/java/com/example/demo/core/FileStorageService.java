package com.example.demo.core;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private final FileUploadValidator fileUploadValidator;

    private Path uploadPath;

    public FileStorageService(FileUploadValidator fileUploadValidator) {
        this.fileUploadValidator = fileUploadValidator;
    }

    @PostConstruct
    public void init() throws IOException {
        uploadPath = Paths.get("./uploads").toAbsolutePath().normalize();
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
    }

    public String storeImage(byte[] imageBytes, String extension) throws IOException {
        String canonicalExtension = fileUploadValidator.canonicalImageExtension(imageBytes, extension);
        String filename = UUID.randomUUID() + "." + canonicalExtension;
        Path targetPath = uploadPath.resolve(filename).normalize();
        if (!targetPath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid upload path");
        }
        Files.write(targetPath, imageBytes);
        return "/uploads/" + filename;
    }

    public void deleteFile(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        Path safeName = Paths.get(fileName).getFileName();
        if (safeName == null) {
            return;
        }
        Path path = uploadPath.resolve(safeName).normalize();
        if (!path.startsWith(uploadPath)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        Files.deleteIfExists(path);
    }
}
