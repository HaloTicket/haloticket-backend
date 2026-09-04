package com.haloticket.media_service.controller;

import com.haloticket.media_service.dto.response.FileUploadResponse;
import com.haloticket.media_service.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteFile(@RequestParam String key) {
        s3StorageService.deleteFile(key);
        return ResponseEntity.noContent().build();
    }
}
