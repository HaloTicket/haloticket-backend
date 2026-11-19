package com.haloticket.media_service.service;

import com.haloticket.media_service.dto.response.FileUploadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Đuôi tệp lấy từ loại nội dung (không tin tên tệp của client) và thư mục đích bị giới hạn. */
class S3StorageServiceTest {

    private S3StorageService service;

    @BeforeEach
    void setUp() {
        service = new S3StorageService(mock(S3Client.class));
        ReflectionTestUtils.setField(service, "bucketName", "bucket");
        ReflectionTestUtils.setField(service, "region", "ap-southeast-1");
        ReflectionTestUtils.setField(service, "endpoint", "");
    }

    @Test
    void extensionComesFromTheContentTypeNotFromTheClientFileName() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.html", "image/png", new byte[] {1, 2, 3});

        FileUploadResponse response = service.uploadImage(file, "avatars");

        assertThat(response.getFileName()).startsWith("avatars/").endsWith(".png").doesNotContain("html");
    }

    @Test
    void contentTypesOutsideTheImageAllowListAreRejected() {
        for (String type : new String[] {"text/html", "application/pdf", "image/x-icon", "video/mp4"}) {
            MockMultipartFile file = new MockMultipartFile("file", "a.bin", type, new byte[] {1});
            assertThatThrownBy(() -> service.uploadImage(file, "media")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void base64UploadAlsoUsesTheAllowList() {
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[] {1, 2});
        assertThat(service.uploadBase64Image(png, "gallery").getFileName()).startsWith("gallery/").endsWith(".png");

        String html = "data:image/html;base64," + Base64.getEncoder().encodeToString(new byte[] {1, 2});
        assertThatThrownBy(() -> service.uploadBase64Image(html, "gallery")).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void folderIsLimitedToSafeSegments() {
        assertThat(S3StorageService.sanitizeFolder("avatars")).isEqualTo("avatars");
        assertThat(S3StorageService.sanitizeFolder(" events/banners ")).isEqualTo("events/banners");
        for (String bad : new String[] {null, "", "  ", "../secret", "a/../b", "/abs", "a//b", "a/b/c/d", "dấu", "a b", "x?y"}) {
            assertThat(S3StorageService.sanitizeFolder(bad)).as(String.valueOf(bad)).isEqualTo("media");
        }
    }
}
