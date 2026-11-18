package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Thông tin nhà tổ chức hiển thị cùng sự kiện. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerDto {
    private String id;
    private String userId;
    private String name;
    private String avatar;
    private String description;
}
