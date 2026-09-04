package com.haloticket.media_service.service;

import com.haloticket.media_service.dto.response.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageService {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket:haloticket-media-bucket}")
    private String bucketName;

    @Value("${aws.s3.region:ap-southeast-1}")
    private String region;

    @Value("${aws.s3.endpoint:}")
    private String endpoint;

    @Value("${aws.s3.access-key-id:dummy-access-key}")
    private String accessKey;

    private static final String LOCAL_UPLOAD_DIR = "uploads";

    public FileUploadResponse uploadImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File upload cannot be empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files (JPG, PNG, WEBP, GIF, SVG) are allowed");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = ".jpg";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String folderPrefix = (folder != null && !folder.trim().isEmpty()) ? folder.trim() : "media";
        String fileName = UUID.randomUUID() + extension;
        String key = folderPrefix + "/" + fileName;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read upload file bytes", e);
        }

        if (isS3Configured()) {
            try (InputStream inputStream = file.getInputStream()) {
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType(contentType)
                        .build();

                s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

                String fileUrl = buildPublicUrl(key);
                log.info("Successfully uploaded file to S3: {}", fileUrl);

                return FileUploadResponse.builder()
                        .imageUrl(fileUrl)
                        .fileName(key)
                        .fileSize(file.getSize())
                        .contentType(contentType)
                        .build();

            } catch (Exception e) {
                log.warn("S3 upload failed ({}), falling back to local disk storage", e.getMessage());
            }
        }

        return saveLocally(bytes, folderPrefix, fileName, file.getSize(), contentType);
    }

    public FileUploadResponse uploadBase64Image(String base64Data, String folder) {
        if (base64Data == null || !base64Data.startsWith("data:image/")) {
            throw new IllegalArgumentException("Invalid Base64 image data");
        }

        try {
            String[] parts = base64Data.split(",");
            String header = parts[0];
            String data = parts[1];

            String contentType = header.substring(header.indexOf(":") + 1, header.indexOf(";"));
            String extension = ".jpg";
            if (contentType.contains("png")) extension = ".png";
            else if (contentType.contains("webp")) extension = ".webp";
            else if (contentType.contains("gif")) extension = ".gif";

            byte[] imageBytes = Base64.getDecoder().decode(data);
            String folderPrefix = (folder != null && !folder.trim().isEmpty()) ? folder.trim() : "media";
            String fileName = UUID.randomUUID() + extension;
            String key = folderPrefix + "/" + fileName;

            if (isS3Configured()) {
                try {
                    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .contentType(contentType)
                            .build();

                    s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(new ByteArrayInputStream(imageBytes), imageBytes.length));

                    String fileUrl = buildPublicUrl(key);
                    log.info("Successfully uploaded Base64 image to S3: {}", fileUrl);

                    return FileUploadResponse.builder()
                            .imageUrl(fileUrl)
                            .fileName(key)
                            .fileSize((long) imageBytes.length)
                            .contentType(contentType)
                            .build();
                } catch (Exception e) {
                    log.warn("S3 upload failed ({}), falling back to local disk storage", e.getMessage());
                }
            }

            return saveLocally(imageBytes, folderPrefix, fileName, (long) imageBytes.length, contentType);
        } catch (Exception e) {
            log.error("Failed to process Base64 image: {}", e.getMessage());
            throw new RuntimeException("Could not upload Base64 image: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String fileKey) {
        if (fileKey == null || fileKey.trim().isEmpty()) return;

        if (isS3Configured()) {
            try {
                DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(fileKey)
                        .build();

                s3Client.deleteObject(deleteObjectRequest);
                log.info("Deleted file from S3: {}", fileKey);
                return;
            } catch (Exception e) {
                log.warn("Failed to delete S3 file ({}): {}", fileKey, e.getMessage());
            }
        }

        try {
            Path filePath = Paths.get(LOCAL_UPLOAD_DIR, fileKey);
            Files.deleteIfExists(filePath);
            log.info("Deleted local file: {}", fileKey);
        } catch (IOException e) {
            log.warn("Failed to delete local file ({}): {}", fileKey, e.getMessage());
        }
    }

    private FileUploadResponse saveLocally(byte[] bytes, String folder, String fileName, long fileSize, String contentType) {
        try {
            Path targetDir = Paths.get(LOCAL_UPLOAD_DIR, folder);
            Files.createDirectories(targetDir);
            Path filePath = targetDir.resolve(fileName);
            Files.write(filePath, bytes);

            String localUrl = "http://localhost:8888/api/media/files/" + folder + "/" + fileName;
            log.info("Successfully saved file locally: {}", localUrl);

            return FileUploadResponse.builder()
                    .imageUrl(localUrl)
                    .fileName(folder + "/" + fileName)
                    .fileSize(fileSize)
                    .contentType(contentType)
                    .build();
        } catch (IOException e) {
            log.error("Failed to save file locally: {}", e.getMessage());
            throw new RuntimeException("Failed to save file locally: " + e.getMessage(), e);
        }
    }

    private boolean isS3Configured() {
        return accessKey != null && !accessKey.trim().isEmpty() && !"dummy-access-key".equalsIgnoreCase(accessKey);
    }

    private String buildPublicUrl(String key) {
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            return cleanEndpoint + "/" + bucketName + "/" + key;
        }
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, key);
    }
}

