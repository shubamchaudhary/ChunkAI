package com.examprep.core.service.impl;

import com.examprep.core.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@Slf4j
public class LocalFileStorageService implements FileStorageService {
    
    @Value("${file.storage.directory:./uploads}")
    private String storageDirectory;
    
    @Override
    public UUID saveFile(MultipartFile file, UUID documentId) throws Exception {
        try {
            // Create storage directory if it doesn't exist
            Path storagePath = Paths.get(storageDirectory);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
            }
            
            // Create file path: storageDirectory/documentId.extension
            String extension = getFileExtension(file.getOriginalFilename());
            String fileName = documentId.toString() + (extension.isEmpty() ? "" : "." + extension);
            Path filePath = storagePath.resolve(fileName);
            
            // Save file
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            
            log.info("Saved file for document {} to {}", documentId, filePath);
            return documentId;
        } catch (IOException e) {
            log.error("Failed to save file for document {}", documentId, e);
            throw new RuntimeException("Failed to save file", e);
        }
    }
    
    @Override
    public InputStream getFile(UUID documentId) throws Exception {
        try {
            Path storagePath = Paths.get(storageDirectory);
            
            // Try to find file with any extension
            Path filePath = findFileByDocumentId(storagePath, documentId);
            
            if (filePath == null || !Files.exists(filePath)) {
                throw new RuntimeException("File not found for document: " + documentId);
            }
            
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            log.error("Failed to retrieve file for document {}", documentId, e);
            throw new RuntimeException("Failed to retrieve file", e);
        }
    }
    
    @Override
    public void deleteFile(UUID documentId) throws Exception {
        try {
            Path storagePath = Paths.get(storageDirectory);
            Path filePath = findFileByDocumentId(storagePath, documentId);
            
            if (filePath != null && Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Deleted file for document {}", documentId);
            }
        } catch (IOException e) {
            log.error("Failed to delete file for document {}", documentId, e);
            throw new RuntimeException("Failed to delete file", e);
        }
    }
    
    @Override
    public boolean fileExists(UUID documentId) {
        try {
            Path storagePath = Paths.get(storageDirectory);
            Path filePath = findFileByDocumentId(storagePath, documentId);
            return filePath != null && Files.exists(filePath);
        } catch (Exception e) {
            return false;
        }
    }
    
    private Path findFileByDocumentId(Path storagePath, UUID documentId) throws IOException {
        if (!Files.exists(storagePath)) {
            return null;
        }
        
        String documentIdStr = documentId.toString();
        return Files.list(storagePath)
            .filter(path -> {
                String fileName = path.getFileName().toString();
                return fileName.startsWith(documentIdStr);
            })
            .findFirst()
            .orElse(null);
    }
    
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase();
    }
}

