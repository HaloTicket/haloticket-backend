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
 * Sales figures of one event for a period. Only orders that count as sold (PAID, CONFIRMED) are included; revenue is whole
 * VND. The period is half open, [from, to), in the same local time the order timestamps are stored in.
 */
@Service
@RequiredArgsConstructor
public class BookingStatsService {

    static final List<BookingStatus> SOLD = List.of(BookingStatus.PAID, BookingStatus.CONFIRMED);
    private static final LocalDateTime EARLIEST = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final BookingRepository bookingRepository;

    public record DailySales(LocalDate date, long ticketsSold, long revenue) {}

    public record Stats(long buyers, long ticketsSold, long revenue, List<DailySales> daily) {}

    /** from or to null means unbounded on that side. */
    @Transactional(readOnly = true)
    public Stats statsFor(String eventId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime start = from == null ? EARLIEST : from;
        LocalDateTime end = to == null ? LATEST : to;
        if (!start.isBefore(end)) return new Stats(0, 0, 0, List.of());
        return summarise(bookingRepository.findPaidOrderRows(eventId, SOLD, start, end));
    }

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
