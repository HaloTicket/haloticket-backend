package com.haloticket.media_service.controller;

import com.haloticket.media_service.dto.response.FileUploadResponse;
import com.haloticket.media_service.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * API tải ảnh lên và xóa ảnh trên S3 ({@code /api/media}).
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final S3StorageService s3StorageService;

    /**
     * Tải một tệp ảnh dạng multipart lên thư mục chỉ định trên S3.
     *
     * @param file tệp ảnh
     * @param folder thư mục đích, mặc định {@code media}
     * @return thông tin tệp đã tải lên (URL, khóa, kích thước, loại nội dung)
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "media") String folder) {
        return ResponseEntity.ok(s3StorageService.uploadImage(file, folder));
    }

    /**
     * Tải ảnh dạng chuỗi Base64 (data URL) lên S3.
     *
     * @param payload map chứa {@code data} (data URL ảnh) và {@code folder} (tùy chọn, mặc định {@code media})
     * @return thông tin tệp đã tải lên
     */
    @PostMapping("/upload-base64")
    public ResponseEntity<FileUploadResponse> uploadBase64Image(
            @RequestBody Map<String, String> payload) {
        String base64Data = payload.get("data");
        String folder = payload.getOrDefault("folder", "media");
        return ResponseEntity.ok(s3StorageService.uploadBase64Image(base64Data, folder));
    }

    /**
     * Xóa một tệp trên S3 theo khóa đối tượng.
     *
     * @param key khóa của tệp trong bucket
     * @return HTTP 204 không có thân
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteFile(@RequestParam String key) {
        s3StorageService.deleteFile(key);
        return ResponseEntity.noContent().build();
    }
}
