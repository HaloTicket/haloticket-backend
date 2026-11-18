package com.haloticket.booking_service.service;

import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Số liệu bán vé của một sự kiện trong một khoảng thời gian. Chỉ tính đơn được coi là đã bán (PAID, CONFIRMED); doanh thu là số VND nguyên.
 * Khoảng thời gian nửa mở [from, to), theo giờ địa phương mà thời gian của đơn được lưu.
 */
@Service
@RequiredArgsConstructor
public class BookingStatsService {

    static final List<BookingStatus> SOLD = List.of(BookingStatus.PAID, BookingStatus.CONFIRMED);
    private static final LocalDateTime EARLIEST = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final BookingRepository bookingRepository;

    /** Số vé bán và doanh thu của một ngày. */
    public record DailySales(LocalDate date, long ticketsSold, long revenue) {}

    /** Số liệu tổng hợp: số người mua, số vé bán, doanh thu và số liệu theo ngày. */
    public record Stats(long buyers, long ticketsSold, long revenue, List<DailySales> daily) {}

    /**
     * Tính số liệu bán vé của sự kiện; from hoặc to là null nghĩa là không giới hạn phía đó.
     * @param eventId mã sự kiện
     * @param from đầu khoảng (bao gồm)
     * @param to cuối khoảng (không bao gồm)
     * @return số liệu tổng hợp, rỗng nếu khoảng thời gian không hợp lệ
     */
    @Transactional(readOnly = true)
    public Stats statsFor(String eventId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime start = from == null ? EARLIEST : from;
        LocalDateTime end = to == null ? LATEST : to;
        if (!start.isBefore(end)) return new Stats(0, 0, 0, List.of());
        return summarise(bookingRepository.findPaidOrderRows(eventId, SOLD, start, end));
    }

    /**
     * Gộp các dòng đơn đã thanh toán thành tổng số người mua, vé, doanh thu và số liệu theo ngày (sắp xếp theo ngày).
     * @param rows các đơn đã thanh toán
     * @return số liệu tổng hợp
     */
    static Stats summarise(List<BookingRepository.PaidOrderRow> rows) {
        Set<String> buyers = new HashSet<>();
        TreeMap<LocalDate, long[]> byDay = new TreeMap<>();
        double revenue = 0;
        long tickets = 0;
        for (BookingRepository.PaidOrderRow row : rows) {
            double amount = row.getAmount() == null ? 0 : row.getAmount();
            long count = row.getTickets() == null ? 0 : row.getTickets();
            revenue += amount;
            tickets += count;
            if (row.getCustomerId() != null) buyers.add(row.getCustomerId());
            if (row.getPaidAt() != null) {
                long[] day = byDay.computeIfAbsent(row.getPaidAt().toLocalDate(), d -> new long[2]);
                day[0] += count;
                day[1] += Math.round(amount);
            }
        }
        List<DailySales> daily = byDay.entrySet().stream()
                .map(e -> new DailySales(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new Stats(buyers.size(), tickets, Math.round(revenue), daily);
    }
}
