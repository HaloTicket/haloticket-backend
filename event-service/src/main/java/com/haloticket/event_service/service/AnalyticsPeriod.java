package com.haloticket.event_service.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The window an analytics request covers, [start, end), in local time of the platform zone, and the window of the same length
 * right before it. Timestamps in the data are local date-times, so the windows are local date-times too.
 */
public record AnalyticsPeriod(LocalDateTime start, LocalDateTime end) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_DAYS = 366;

    public static class InvalidPeriodException extends ResponseStatusException {
        public InvalidPeriodException(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    /**
     * from and to (whole days, to included) win over timeRange when both are given; giving only one is an error. timeRange is
     * a rolling window ending now: 24h, 7d, or (default and anything else) 30d.
     */
    public static AnalyticsPeriod resolve(String timeRange, LocalDate from, LocalDate to, LocalDateTime now) {
        if ((from == null) != (to == null)) {
            throw new InvalidPeriodException("from and to must be given together");
        }
        if (from != null) {
            if (to.isBefore(from)) throw new InvalidPeriodException("to is before from");
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw new InvalidPeriodException("a period is at most " + MAX_DAYS + " days");
            }
            return new AnalyticsPeriod(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        }
        if ("24h".equals(timeRange)) return new AnalyticsPeriod(now.minusHours(24), now);
        if ("7d".equals(timeRange)) return new AnalyticsPeriod(now.minusDays(7), now);
        return new AnalyticsPeriod(now.minusDays(30), now);
    }

    public Duration length() {
        return Duration.between(start, end);
    }

    /** The window of the same length that ends where this one starts. */
    public AnalyticsPeriod previous() {
        return new AnalyticsPeriod(start.minus(length()), start);
    }

    /** First day shown, and the last day shown (the day before end when end is exactly midnight). */
    public LocalDate firstDay() {
        return start.toLocalDate();
    }

    public LocalDate lastDay() {
        LocalDateTime last = end.minusNanos(1);
        return last.toLocalDate();
    }
}
