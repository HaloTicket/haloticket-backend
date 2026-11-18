package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.StaffRole;
import lombok.Data;

/** Yêu cầu mời một người vào nhân sự của sự kiện: email người được mời và vai trò. */
@Data
public class EventMemberInviteRequest {
    private String email;
    private StaffRole role;
}
