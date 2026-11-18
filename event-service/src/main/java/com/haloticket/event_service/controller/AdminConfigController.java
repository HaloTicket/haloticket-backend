package com.haloticket.event_service.controller;

import com.haloticket.event_service.entity.EventSectionConfig;
import com.haloticket.event_service.entity.HomepageBanner;
import com.haloticket.event_service.repository.EventSectionConfigRepository;
import com.haloticket.event_service.repository.HomepageBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Cấu hình trang chủ. Thao tác đọc là công khai (trang chủ lấy dữ liệu từ đây); mọi thao tác ghi dành cho quản trị viên nền tảng. */
@RestController
@RequestMapping("/api/events/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final HomepageBannerRepository bannerRepository;
    private final EventSectionConfigRepository sectionConfigRepository;

    // cấu hình trang chủ (banner)
    /**
     * Lấy toàn bộ banner của trang chủ.
     * @return danh sách banner
     */
    @GetMapping("/homepage/banners")
    public ResponseEntity<List<HomepageBanner>> getBanners() {
        return ResponseEntity.ok(bannerRepository.findAll());
    }

    /**
     * Thay toàn bộ danh sách banner trong một giao dịch: nếu một banner bị từ chối hoặc lưu lỗi thì danh sách cũ được giữ nguyên.
     * Banner chưa có mã sẽ được cấp mã mới; tiêu đề bắt buộc, ảnh và liên kết phải hợp lệ.
     * @param newBanners danh sách banner mới
     * @return danh sách banner sau khi lưu
     */
    @PutMapping("/homepage/banners")
    @Transactional
    public ResponseEntity<List<HomepageBanner>> updateBanners(@RequestBody List<HomepageBanner> newBanners) {
        for (HomepageBanner banner : newBanners) {
            if (banner.getId() == null || banner.getId().isBlank()) {
                banner.setId(UUID.randomUUID().toString());
            }
            requireText(banner.getTitle(), "BANNER_TITLE_REQUIRED");
            requireLink(banner.getImageUrl(), "BANNER_IMAGE_INVALID");
            requireLink(banner.getLinkUrl(), "BANNER_LINK_INVALID");
        }
        bannerRepository.deleteAllInBatch(); // xóa hàng loạt chạy ngay, để các lệnh lưu bên dưới không đụng các dòng đang bị thay
        return ResponseEntity.ok(bannerRepository.saveAll(newBanners));
    }

    // cấu hình trang chủ (các mục sự kiện)
    /**
     * Lấy cấu hình các mục sự kiện của trang chủ.
     * @return danh sách mục sự kiện
     */
    @GetMapping("/homepage/sections")
    public ResponseEntity<List<EventSectionConfig>> getEventSections() {
        return ResponseEntity.ok(sectionConfigRepository.findAll());
    }

    /**
     * Thay toàn bộ danh sách mục sự kiện trong một giao dịch, giống như với banner. Tiêu đề bắt buộc, {@code limit} không được âm, ảnh và liên kết quảng cáo phải hợp lệ.
     * @param newSections danh sách mục mới
     * @return danh sách mục sau khi lưu
     */
    @PutMapping("/homepage/sections")
    @Transactional
    public ResponseEntity<List<EventSectionConfig>> updateEventSections(@RequestBody List<EventSectionConfig> newSections) {
        for (EventSectionConfig section : newSections) {
            if (section.getId() == null || section.getId().isBlank()) {
                section.setId(UUID.randomUUID().toString());
            }
            requireText(section.getTitle(), "SECTION_TITLE_REQUIRED");
            if (section.getLimit() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SECTION_LIMIT_INVALID");
            }
            requireLink(section.getAdsImageUrl(), "SECTION_IMAGE_INVALID");
            requireLink(section.getAdsButtonUrl(), "SECTION_LINK_INVALID");
        }
        sectionConfigRepository.deleteAllInBatch();
        return ResponseEntity.ok(sectionConfigRepository.saveAll(newSections));
    }

    /**
     * Bắt buộc một chuỗi có nội dung.
     * @param value chuỗi cần kiểm tra
     * @param code mã lỗi trả về trong thông báo 400
     * @throws ResponseStatusException 400 khi chuỗi rỗng hoặc chỉ có khoảng trắng
     */
    private static void requireText(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        }
    }

    /**
     * Cho phép để trống; nếu có giá trị thì phải là địa chỉ http(s), đường dẫn trong ứng dụng bắt đầu bằng dấu gạch chéo, hoặc ảnh nhúng dạng {@code data:image/}.
     * @param value liên kết cần kiểm tra
     * @param code mã lỗi trả về trong thông báo 400
     * @throws ResponseStatusException 400 khi liên kết không thuộc các dạng trên
     */
    private static void requireLink(String value, String code) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim().toLowerCase();
        if (!(v.startsWith("http://") || v.startsWith("https://") || v.startsWith("/") || v.startsWith("data:image/"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
        }
    }
}
