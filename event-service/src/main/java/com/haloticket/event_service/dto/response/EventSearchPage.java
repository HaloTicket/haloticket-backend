package com.haloticket.event_service.dto.response;

import java.util.List;

/**
 * Một trang kết quả tìm kiếm sự kiện.
 *
 * @param content các sự kiện của trang
 * @param page số trang hiện tại, bắt đầu từ 0
 * @param size kích thước trang đã áp dụng
 * @param totalElements tổng số sự kiện khớp
 * @param totalPages tổng số trang
 */
public record EventSearchPage(List<EventSearchItem> content, int page, int size, long totalElements, int totalPages) {
}
