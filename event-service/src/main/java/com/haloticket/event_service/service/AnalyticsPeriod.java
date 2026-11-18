package com.haloticket.event_service.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Khoảng thời gian mà một yêu cầu thống kê bao phủ, dạng [start, end), theo giờ địa phương của múi giờ nền tảng, kèm khoảng
 * liền trước có cùng độ dài để so sánh. Mốc thời gian trong dữ liệu là local date-time nên các khoảng cũng là local date-time.
 */
public record AnalyticsPeriod(LocalDateTime start, LocalDateTime end) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_DAYS = 366;

    /** Lỗi HTTP 400 khi tham số khoảng thời gian không hợp lệ. */
    public static class InvalidPeriodException extends ResponseStatusException {
        /**
         * Tạo lỗi 400 với thông điệp mô tả tham số sai.
         * @param message lý do khoảng thời gian không hợp lệ
         */
        public InvalidPeriodException(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    /**
     * Xác định khoảng thống kê từ tham số của request. {@code from} và {@code to} (tính theo ngày trọn vẹn, gồm cả ngày
     * {@code to}) được ưu tiên hơn {@code timeRange} khi có đủ cả hai; chỉ có một trong hai là lỗi. {@code timeRange} là cửa sổ
     * trượt kết thúc tại {@code now}: {@code 24h}, {@code 7d}, còn lại (mặc định) là {@code 30d}.
     *
     * @param timeRange cửa sổ trượt, có thể null
     * @param from ngày bắt đầu, phải đi cùng {@code to}
     * @param to ngày kết thúc (tính cả ngày này)
     * @param now thời điểm hiện tại làm mốc kết thúc cho cửa sổ trượt
     * @return khoảng thống kê đã xác định
     * @throws InvalidPeriodException khi chỉ có một trong {@code from}/{@code to}, {@code to} trước {@code from}, hoặc dài quá {@code MAX_DAYS} ngày
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

    /** Độ dài của khoảng thời gian. */
    public Duration length() {
        return Duration.between(start, end);
    }

    /** Khoảng liền trước có cùng độ dài, kết thúc đúng tại điểm bắt đầu của khoảng này. */
    public AnalyticsPeriod previous() {
        return new AnalyticsPeriod(start.minus(length()), start);
    }

    /** Ngày đầu tiên được hiển thị trong khoảng. */
    public LocalDate firstDay() {
        return start.toLocalDate();
    }

    /** Ngày cuối cùng được hiển thị: tính lùi 1 nano giây từ {@code end}, nên khi {@code end} đúng nửa đêm thì là ngày liền trước. */
    public LocalDate lastDay() {
        LocalDateTime last = end.minusNanos(1);
        return last.toLocalDate();
    }
}
