package com.example.demo.core;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private final FileUploadValidator fileUploadValidator;
    private final UploadPathProvider uploadPathProvider;

    public FileStorageService(FileUploadValidator fileUploadValidator,
                              UploadPathProvider uploadPathProvider) {
        this.fileUploadValidator = fileUploadValidator;
        this.uploadPathProvider = uploadPathProvider;
    }

    public String storeImage(byte[] imageBytes, String extension) throws IOException {
        String canonicalExtension = fileUploadValidator.canonicalImageExtension(imageBytes, extension);
        String filename = UUID.randomUUID() + "." + canonicalExtension;
        Path targetPath = uploadPathProvider.resolve(filename);
        Files.write(targetPath, imageBytes);
        return uploadPathProvider.urlOf(filename);
    }

    public void deleteFile(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        Path safeName = Paths.get(fileName).getFileName();
        if (safeName == null) {
            return;
        }
        Path path = uploadPathProvider.resolve(safeName.toString());
        Files.deleteIfExists(path);
    }
}
