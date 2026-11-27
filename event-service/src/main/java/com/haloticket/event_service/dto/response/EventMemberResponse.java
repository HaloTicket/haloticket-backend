package com.haloticket.event_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.haloticket.event_service.enums.StaffRole;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/** Một thành viên nhân sự của sự kiện: thông tin người dùng, vai trò, trạng thái và hạn của lời mời (nếu còn chờ). */
@Data
@Builder
public class EventMemberResponse {
    private String id;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String userId;
    private String name;
    private String email;
    private String avatar;
    private StaffRole role;
    private String status;
    private LocalDateTime addedAt;
    private LocalDateTime invitationExpiresAt;
}
