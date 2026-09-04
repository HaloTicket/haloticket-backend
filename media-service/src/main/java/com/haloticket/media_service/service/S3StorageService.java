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
import java.io.InputStream;
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

        String actualBucket = System.getProperty("AWS_S3_BUCKET", bucketName);

        try (InputStream inputStream = file.getInputStream()) {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(actualBucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

            String fileUrl = buildPublicUrl(actualBucket, key);
            log.info("Successfully uploaded file to AWS S3: {}", fileUrl);

            return FileUploadResponse.builder()
                    .imageUrl(fileUrl)
                    .fileName(key)
                    .fileSize(file.getSize())
                    .contentType(contentType)
                    .build();

        } catch (Exception e) {
            log.error("Failed to upload image file to AWS S3 (Bucket: {}): {}", actualBucket, e.getMessage(), e);
            throw new RuntimeException("AWS S3 Upload Error (" + actualBucket + "): " + e.getMessage(), e);
        }
    }

    public FileUploadResponse uploadBase64Image(String base64Data, String folder) {
        if (base64Data == null || !base64Data.startsWith("data:image/")) {
            throw new IllegalArgumentException("Invalid Base64 image data");
        }

        String actualBucket = System.getProperty("AWS_S3_BUCKET", bucketName);

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

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(actualBucket)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(new ByteArrayInputStream(imageBytes), imageBytes.length));

            String fileUrl = buildPublicUrl(actualBucket, key);
            log.info("Successfully uploaded Base64 image to AWS S3: {}", fileUrl);

            return FileUploadResponse.builder()
                    .imageUrl(fileUrl)
                    .fileName(key)
                    .fileSize((long) imageBytes.length)
                    .contentType(contentType)
                    .build();

        } catch (Exception e) {
            log.error("Failed to upload Base64 image to AWS S3 (Bucket: {}): {}", actualBucket, e.getMessage(), e);
            throw new RuntimeException("AWS S3 Base64 Upload Error (" + actualBucket + "): " + e.getMessage(), e);
        }
    }

    public void deleteFile(String fileKey) {
        if (fileKey == null || fileKey.trim().isEmpty()) return;

        String actualBucket = System.getProperty("AWS_S3_BUCKET", bucketName);

        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(actualBucket)
                    .key(fileKey)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted file from AWS S3: {}", fileKey);
        } catch (Exception e) {
            log.error("Failed to delete AWS S3 file ({}): {}", fileKey, e.getMessage());
            throw new RuntimeException("AWS S3 Delete Error: " + e.getMessage(), e);
        }
    }

    private String buildPublicUrl(String bucket, String key) {
        String actualRegion = System.getProperty("AWS_REGION", region);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            return cleanEndpoint + "/" + bucket + "/" + key;
        }
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, actualRegion, key);
    }
}
