package com.haloticket.event_service.service.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 429 INVITATION_RATE_LIMITED with the Retry-After header (seconds). */
final class RateLimitedException extends ResponseStatusException {

    private final HttpHeaders headers = new HttpHeaders();

    RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "INVITATION_RATE_LIMITED");
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)));
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }
}
