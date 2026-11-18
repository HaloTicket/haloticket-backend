package com.haloticket.event_service.dto.request;

import lombok.Data;

/** Yêu cầu chứa token lời mời tham gia nhân sự sự kiện, dùng cho xem trước, chấp nhận và từ chối. */
@Data
public class MemberInvitationTokenRequest {
    private String token;
}
