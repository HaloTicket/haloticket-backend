package com.haloticket.user_service.auth.domain;

public enum OtpStatus {
    PENDING,
    VERIFIED,
    CONSUMED,
    EXPIRED,
    LOCKED
}
