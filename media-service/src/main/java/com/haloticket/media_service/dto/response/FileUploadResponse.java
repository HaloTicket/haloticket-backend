package com.haloticket.media_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kết quả tải tệp lên S3: URL truy cập công khai, khóa đối tượng (lưu trong {@code fileName}), kích thước và loại nội dung.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {
    private String imageUrl;
    private String fileName;
    private Long fileSize;
    private String contentType;
}
