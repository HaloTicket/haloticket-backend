package com.haloticket.event_service.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Chạy các câu {@code ALTER TABLE} khi khởi động để đổi các cột văn bản dài (URL, mô tả, địa chỉ) sang kiểu {@code TEXT}. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Thực thi lần lượt từng câu lệnh đổi kiểu cột. Câu lỗi chỉ được ghi cảnh báo, không chặn khởi động.
     * @param args tham số dòng lệnh, không dùng
     */
    @Override
    public void run(String... args) {
        log.info("Running database schema column type checks/updates for event_db...");
        String[] alterQueries = {
            "ALTER TABLE events ALTER COLUMN banner_url TYPE TEXT;",
            "ALTER TABLE events ALTER COLUMN thumbnail_url TYPE TEXT;",
            "ALTER TABLE events ALTER COLUMN description TYPE TEXT;",
            "ALTER TABLE events ALTER COLUMN confirmation_message TYPE TEXT;",
            "ALTER TABLE events ALTER COLUMN address TYPE TEXT;",
            "ALTER TABLE events ALTER COLUMN location_name TYPE TEXT;",
            "ALTER TABLE event_organizers ALTER COLUMN avatar_url TYPE TEXT;",
            "ALTER TABLE event_organizers ALTER COLUMN description TYPE TEXT;",
            "ALTER TABLE event_gallery ALTER COLUMN image_url TYPE TEXT;"
        };

        for (String query : alterQueries) {
            try {
                jdbcTemplate.execute(query);
                log.info("Successfully executed: {}", query);
            } catch (Exception e) {
                log.warn("Schema update query notice ({}): {}", query, e.getMessage());
            }
        }
    }
}
