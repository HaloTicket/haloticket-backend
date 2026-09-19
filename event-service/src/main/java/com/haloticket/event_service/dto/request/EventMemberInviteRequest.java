package com.haloticket.event_service.dto.request;

import com.haloticket.event_service.enums.StaffRole;
import lombok.Data;

@Data
public class EventMemberInviteRequest {
    private String email;
    private StaffRole role;
}
