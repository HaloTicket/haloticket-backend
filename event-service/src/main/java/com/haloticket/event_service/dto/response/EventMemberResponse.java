package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.enums.StaffRole;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class EventMemberResponse {
    private String id;
    private String name;
    private String email;
    private String avatar;
    private StaffRole role;
    private String status;
    private LocalDateTime addedAt;
}
