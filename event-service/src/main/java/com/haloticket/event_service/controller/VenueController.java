package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;
import com.haloticket.event_service.dto.response.VenueSeatmapsResponse;
import com.haloticket.event_service.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** API địa điểm tổ chức sự kiện: đọc công khai, ghi dành cho quản trị viên nền tảng. */
@RestController
@RequestMapping("/api/events/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    /**
     * Lấy danh sách địa điểm.
     * @return danh sách địa điểm
     */
    @GetMapping
    public ResponseEntity<List<VenueResponse>> getAllVenues() {
        return ResponseEntity.ok(venueService.getAllVenues());
    }

    /**
     * Lấy chi tiết một địa điểm.
     * @param id mã địa điểm
     * @return địa điểm tìm được
     */
    @GetMapping("/{id}")
    public ResponseEntity<VenueResponse> getVenueById(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.getVenueById(id));
    }

    /**
     * Tạo địa điểm mới.
     * @param request dữ liệu địa điểm
     * @return địa điểm vừa tạo với mã 201
     */
    @PostMapping
    public ResponseEntity<VenueResponse> createVenue(@RequestBody VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.createVenue(request));
    }

    /**
     * Cập nhật địa điểm.
     * @param id mã địa điểm
     * @param request dữ liệu mới
     * @return địa điểm sau khi sửa
     */
    @PutMapping("/{id}")
    public ResponseEntity<VenueResponse> updateVenue(
            @PathVariable Long id,
            @RequestBody VenueRequest request) {
        return ResponseEntity.ok(venueService.updateVenue(id, request));
    }

    /**
     * Đảo trạng thái đã xác minh của địa điểm.
     * @param id mã địa điểm
     * @return địa điểm sau khi đảo
     */
    @PatchMapping("/{id}/verify")
    public ResponseEntity<VenueResponse> toggleVerification(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.toggleVerification(id));
    }

    /**
     * Xóa địa điểm.
     * @param id mã địa điểm
     * @return 204 không có thân
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVenue(@PathVariable Long id) {
        venueService.deleteVenue(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lấy các sơ đồ chỗ ngồi của địa điểm.
     * @param id mã địa điểm
     * @return danh sách sơ đồ chỗ ngồi
     */
    @GetMapping("/{id}/seatmaps")
    public ResponseEntity<VenueSeatmapsResponse> getVenueSeatmaps(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.getVenueSeatmaps(id));
    }

    /**
     * Lưu mẫu sơ đồ chỗ ngồi của địa điểm.
     * @param id mã địa điểm
     * @param body chứa khóa {@code seatmapTemplate} là nội dung mẫu
     * @return địa điểm sau khi lưu mẫu
     */
    @PutMapping("/{id}/seatmap-template")
    public ResponseEntity<VenueResponse> saveVenueSeatmapTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String template = body.get("seatmapTemplate");
        return ResponseEntity.ok(venueService.saveVenueSeatmapTemplate(id, template));
    }
}
