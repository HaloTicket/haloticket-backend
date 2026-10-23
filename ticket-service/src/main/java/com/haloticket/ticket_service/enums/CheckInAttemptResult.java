package com.haloticket.ticket_service.enums;

/**
 * Outcome of one scan, stored in checkin_attempts.result (a varchar with a CHECK constraint that Hibernate never updates:
 * adding a value needs a new SQL migration, see haloticket-infrastructure/postgres/migrations).
 */
public enum CheckInAttemptResult {
    SUCCESS,
    FAILED_SIGNATURE,
    FAILED_ALREADY_IN,
    OUTSIDE_CHECKIN_WINDOW,
    TICKET_NOT_FOUND,
    WRONG_EVENT,
    WRONG_SHOWTIME,
    TICKET_LOCKED,
    WINDOW_UNVERIFIED
}
