package com.haloticket.media_service.controller;

import com.haloticket.media_service.dto.response.FileUploadResponse;
import com.haloticket.media_service.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final S3StorageService s3StorageService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "media") String folder) {
        return ResponseEntity.ok(s3StorageService.uploadImage(file, folder));
    }

    @PostMapping("/upload-base64")
    public ResponseEntity<FileUploadResponse> uploadBase64Image(
            @RequestBody Map<String, String> payload) {
        String base64Data = payload.get("data");
        String folder = payload.getOrDefault("folder", "media");
        return ResponseEntity.ok(s3StorageService.uploadBase64Image(base64Data, folder));
    }

    @GetMapping("/files/{folder}/{fileName}")
    public ResponseEntity<Resource> getLocalFile(@PathVariable String folder, @PathVariable String fileName) {
        try {
            Path filePath = Paths.get("uploads", folder, fileName);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() || resource.isReadable()) {
                String contentType = Files.probeContentType(filePath);
                if (contentType == null) contentType = "image/jpeg";
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteFile(@RequestParam String key) {
        s3StorageService.deleteFile(key);
        return ResponseEntity.noContent().build();
    }
}

