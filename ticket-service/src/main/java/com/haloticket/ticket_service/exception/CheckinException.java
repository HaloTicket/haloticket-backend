package com.haloticket.ticket_service.exception;

import lombok.Getter;

@Getter
public class CheckinException extends RuntimeException {
    private final String code;

    public CheckinException(String code, String message) {
        super(message);
        this.code = code;
    }
}
