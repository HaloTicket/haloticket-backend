package com.haloticket.event_service.dto.response;

import java.time.LocalDateTime;

/**
 * Một sự kiện trong kết quả tìm kiếm công khai. Gọn hơn {@link EventResponse}: không kèm vé, suất diễn hay dữ liệu quản trị,
 * nên tìm kiếm không phải gọi sang ticket-service cho từng sự kiện.
 *
 * @param id mã sự kiện
 * @param title tiêu đề
 * @param bannerUrl ảnh bìa
 * @param thumbnailUrl ảnh thu nhỏ
 * @param locationName tên địa điểm
 * @param address địa chỉ
 * @param city thành phố
 * @param categoryId mã danh mục
 * @param categoryName tên danh mục
 * @param startTime giờ bắt đầu
 * @param endTime giờ kết thúc
 * @param saleStartTime giờ mở bán
 * @param saleEndTime giờ đóng bán
 * @param minPrice giá thấp nhất; {@code null} nếu chưa có giá
 */
public record EventSearchItem(
        String id,
        String title,
        String bannerUrl,
        String thumbnailUrl,
        String locationName,
        String address,
        String city,
        String categoryId,
        String categoryName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime saleStartTime,
        LocalDateTime saleEndTime,
        Double minPrice) {
}
