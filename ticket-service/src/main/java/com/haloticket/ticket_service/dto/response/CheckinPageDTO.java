package com.haloticket.ticket_service.dto.response;

import java.util.List;

/** Một trang danh sách người đã vào cửa, mới nhất trước, kèm thông tin phân trang (trang đếm từ 0). */
public record CheckinPageDTO(List<TicketCheckinDTO> content, int page, int size, long totalElements, int totalPages) {}
