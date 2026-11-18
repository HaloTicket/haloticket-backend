package com.haloticket.user_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Số liệu tổng hợp cho trang quản lý người dùng, trả về trong một lần gọi thay vì mỗi vai trò một yêu cầu danh sách. */
@Data
@Builder
public class AdminUserStatsResponse {
    private long total;
    /** Tên vai trò và số người giữ vai trò đó (người có hai vai trò được tính ở cả hai). */
    private Map<String, Long> byRole;
    /** Tên trạng thái và số người đang ở trạng thái đó. */
    private Map<String, Long> byStatus;
}
