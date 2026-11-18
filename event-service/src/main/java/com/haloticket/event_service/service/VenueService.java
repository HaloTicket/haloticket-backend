package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;
import com.haloticket.event_service.dto.response.VenueSeatmapsResponse;

import java.util.List;

/** Nghiệp vụ quản lý địa điểm tổ chức sự kiện và mẫu sơ đồ chỗ ngồi của từng địa điểm. */
public interface VenueService {
    /** Lấy toàn bộ địa điểm; nếu bảng còn trống thì tự khởi tạo từ dữ liệu sự kiện có sẵn. */
    List<VenueResponse> getAllVenues();
    /** Lấy một địa điểm theo id. */
    VenueResponse getVenueById(Long id);
    /** Tạo địa điểm mới; nếu đã có địa điểm trùng tên (không phân biệt hoa thường) thì cập nhật địa điểm đó. */
    VenueResponse createVenue(VenueRequest request);
    /** Cập nhật các trường được gửi lên của địa điểm, trường null giữ nguyên. */
    VenueResponse updateVenue(Long id, VenueRequest request);
    /** Đảo trạng thái đã xác minh của địa điểm. */
    VenueResponse toggleVerification(Long id);
    /** Xóa địa điểm theo id. */
    void deleteVenue(Long id);
    /** Lấy mẫu sơ đồ của admin và các sơ đồ do nhà tổ chức dựng cho những sự kiện diễn ra tại địa điểm. */
    VenueSeatmapsResponse getVenueSeatmaps(Long id);
    /** Lưu mẫu sơ đồ chỗ ngồi (JSON) mà admin đặt cho địa điểm. */
    VenueResponse saveVenueSeatmapTemplate(Long id, String seatmapTemplate);
}
