package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.response.VoucherResponse;
import com.haloticket.booking_service.exception.VoucherNotApplicableException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Decides whether a voucher may be used on an order and how much it takes off. Every refusal is an
 * {@link VoucherNotApplicableException} whose message is written for the buyer (the order is answered with 400), so a
 * voucher that does not apply is never silently ignored.
 */
public final class VoucherPolicy {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    };

    private VoucherPolicy() {}

    /**
     * @param tickets          tickets in the order
     * @param subtotal         price of the order before the discount
     * @param showtimeId       showtime the order is for (a voucher limited to showtimes needs it in its list)
     * @param priorOrdersOfBuyer how many paid orders this buyer already made with the voucher; only asked when the
     *                         voucher limits orders per buyer
     * @return the discount, never above the subtotal
     */
    public static double discountFor(VoucherResponse voucher, int tickets, double subtotal, String showtimeId,
                                     LocalDateTime now, LongSupplier priorOrdersOfBuyer) {
        if (voucher == null) {
            throw new VoucherNotApplicableException("Mã giảm giá không tồn tại cho sự kiện này.");
        }
        if (!Boolean.TRUE.equals(voucher.getIsActive()) || !"ACTIVE".equalsIgnoreCase(voucher.getStatus())) {
            throw new VoucherNotApplicableException("Mã giảm giá hiện không khả dụng.");
        }

        LocalDateTime start = parse(voucher.getStartDate());
        LocalDateTime end = parse(voucher.getEndDate());
        if (start != null && now.isBefore(start)) {
            throw new VoucherNotApplicableException("Mã giảm giá chưa có hiệu lực.");
        }
        if (end != null && now.isAfter(end)) {
            throw new VoucherNotApplicableException("Mã giảm giá đã hết hạn.");
        }

        if ("LIMITED".equalsIgnoreCase(voucher.getApplyTicketLimitType()) && voucher.getTicketLimitCount() != null) {
            int used = voucher.getUsedCount() != null ? voucher.getUsedCount() : 0;
            int left = Math.max(0, voucher.getTicketLimitCount() - used);
            if (left == 0) {
                throw new VoucherNotApplicableException("Mã giảm giá đã hết lượt sử dụng.");
            }
            if (tickets > left) {
                throw new VoucherNotApplicableException("Mã giảm giá chỉ còn " + left + " lượt, không đủ cho đơn " + tickets + " vé.");
            }
        }

        if (voucher.getMinTicketsPerOrder() != null && tickets < voucher.getMinTicketsPerOrder()) {
            throw new VoucherNotApplicableException("Mã giảm giá chỉ áp dụng cho đơn từ " + voucher.getMinTicketsPerOrder() + " vé.");
        }
        if (voucher.getMaxTicketsPerOrder() != null && tickets > voucher.getMaxTicketsPerOrder()) {
            throw new VoucherNotApplicableException("Mã giảm giá chỉ áp dụng cho đơn tối đa " + voucher.getMaxTicketsPerOrder() + " vé.");
        }

        if (voucher.getMaxOrdersPerBuyer() != null && priorOrdersOfBuyer.getAsLong() >= voucher.getMaxOrdersPerBuyer()) {
            throw new VoucherNotApplicableException("Bạn đã dùng mã giảm giá này tối đa " + voucher.getMaxOrdersPerBuyer() + " lần.");
        }

        if ("LIMITED_SHOWTIMES".equalsIgnoreCase(voucher.getApplyScope())) {
            List<String> showtimes = voucher.getApplicableShowtimes();
            if (showtimeId == null || showtimes == null || !showtimes.contains(showtimeId)) {
                throw new VoucherNotApplicableException("Mã giảm giá không áp dụng cho suất diễn này.");
            }
        }

        double value = voucher.getDiscountValue() != null ? voucher.getDiscountValue() : 0.0;
        double discount = "PERCENTAGE".equalsIgnoreCase(voucher.getDiscountType())
                ? Math.round(subtotal * (value / 100.0))
                : value;
        return Math.max(0.0, Math.min(discount, subtotal));
    }

    /** A blank or unreadable date means no limit on that side. */
    static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) return null;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(text.trim(), format);
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        return null;
    }
}
