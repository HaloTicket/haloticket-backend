package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.CategoryStatus;
import lombok.Data;

/** Dữ liệu quản trị viên gửi khi tạo hoặc sửa danh mục. Khi cập nhật, trường nào không gửi thì giữ nguyên giá trị cũ. */
@Data
public class CategoryRequest {
    private String name;
    private String description;
    private String slug;
    private String imageUrl;
    private String icon;
    private Boolean active;
    private CategoryStatus status;
}
