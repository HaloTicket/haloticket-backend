package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.response.VoucherResponse;
import com.haloticket.booking_service.exception.VoucherNotApplicableException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Quyết định voucher có dùng được cho đơn không và giảm bao nhiêu. Mọi từ chối đều là
 * {@link VoucherNotApplicableException} với thông điệp viết cho người mua (đơn được trả lời bằng HTTP 400), nên voucher
 * không áp dụng được sẽ không bao giờ bị lặng lẽ bỏ qua.
 */
public final class VoucherPolicy {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    };

    /** Lớp tiện ích không tạo đối tượng. */
    private VoucherPolicy() {}

    /**
     * Tính số tiền giảm của voucher cho đơn: kiểm tra trạng thái, thời gian hiệu lực, giới hạn lượt dùng, số vé mỗi đơn,
     * số đơn mỗi người mua và phạm vi suất chiếu; sau đó tính theo phần trăm hoặc số tiền cố định.
     * @param voucher voucher cần áp dụng, null nghĩa là không tồn tại
     * @param tickets số vé trong đơn
     * @param subtotal giá đơn trước khi giảm
     * @param showtimeId suất chiếu của đơn (voucher giới hạn theo suất chiếu cần có suất này trong danh sách)
     * @param now thời điểm hiện tại để kiểm tra hiệu lực
     * @param priorOrdersOfBuyer số đơn đã thanh toán của người mua với voucher này; chỉ được hỏi khi voucher giới hạn số đơn mỗi người mua
     * @return số tiền giảm, không bao giờ vượt quá giá đơn
     * @throws VoucherNotApplicableException khi voucher không dùng được cho đơn
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

    /** Đọc thời gian theo các định dạng được hỗ trợ; chuỗi trống hoặc không đọc được nghĩa là không giới hạn phía đó. */
    static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) return null;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(text.trim(), format);
            } catch (RuntimeException ignored) {
                // thử định dạng tiếp theo
            }
        }
        return null;
    }
}
