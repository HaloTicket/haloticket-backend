package com.haloticket.media_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Cấu hình {@code S3Client} cho {@code media-service}. Khi lớp được nạp, thông tin cấu hình trong tệp {@code .env}
 * (nếu có) được đưa vào system property với điều kiện chưa có biến cùng tên.
 */
@Configuration
@Slf4j
public class S3Config {

    static {
        loadEnvFile();
    }

    /**
     * Tìm tệp {@code .env} ở vài vị trí thường gặp, đọc các dòng {@code KEY=VALUE} (bỏ dòng trống và dòng bắt đầu bằng {@code #})
     * rồi đặt vào system property nếu chưa có system property hoặc biến môi trường cùng tên. Chỉ dùng tệp đầu tiên tìm được; lỗi đọc chỉ được ghi log.
     */
    private static void loadEnvFile() {
        String[] possiblePaths = {
                ".env",
                "media-service/.env",
                "../.env",
                "haloticket-backend/media-service/.env"
        };
        for (String p : possiblePaths) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                        String[] parts = line.split("=", 2);
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, value);
                        }
                    }
                    log.info("[S3Config] Successfully loaded .env file from: {}", path.toAbsolutePath());
                    break;
                } catch (Exception e) {
                    log.error("[S3Config] Failed to read .env file at {}: {}", p, e.getMessage());
                }
            }
        }
    }

    @Value("${aws.s3.region:ap-southeast-1}")
    private String region;

    @Value("${aws.s3.access-key-id:dummy-access-key}")
    private String accessKeyId;

    @Value("${aws.s3.secret-access-key:dummy-secret-key}")
    private String secretAccessKey;

    @Value("${aws.s3.endpoint:}")
    private String endpoint;

    /**
     * Tạo {@code S3Client} với khóa truy cập và vùng lấy từ system property ({@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY},
     * {@code AWS_REGION}) nếu có, ngược lại dùng cấu hình {@code aws.s3.*}. Có {@code aws.s3.endpoint} thì ghi đè địa chỉ (ví dụ S3 tương thích như MinIO).
     * Log chỉ in 4 ký tự đầu của khóa truy cập.
     *
     * @return client S3 dùng chung
     */
    @Bean
    public S3Client s3Client() {
        String keyId = System.getProperty("AWS_ACCESS_KEY_ID", accessKeyId);
        String secretKey = System.getProperty("AWS_SECRET_ACCESS_KEY", secretAccessKey);
        String reg = System.getProperty("AWS_REGION", region);

        log.info("[S3Config] Initializing AWS S3Client with Region: {}, AccessKey: {}****", reg, keyId.substring(0, Math.min(4, keyId.length())));

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(reg))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(keyId, secretKey)
                ));

        if (endpoint != null && !endpoint.trim().isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }
}
