package com.haloticket.event_service.service.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Lỗi 429 {@code INVITATION_RATE_LIMITED} kèm header Retry-After (tính bằng giây). */
final class RateLimitedException extends ResponseStatusException {

    private final HttpHeaders headers = new HttpHeaders();

    /**
     * Tạo lỗi 429 với thời gian chờ tối thiểu 1 giây.
     * @param retryAfterSeconds số giây client phải chờ trước khi thử lại
     */
    RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_RATE_LIMITED");
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)));
    }

    /** Trả các header sẽ gắn vào response, gồm {@code Retry-After}. */
    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
