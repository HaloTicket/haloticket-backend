package com.haloticket.event_service.dto.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalUserResponse {
    private String id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String status;
}
