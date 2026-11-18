package com.haloticket.event_service.service;

import com.haloticket.event_service.client.*;
import com.haloticket.event_service.dto.response.AdminEventReportResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.repository.EventRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Báo cáo doanh thu theo sự kiện cho admin nền tảng. Ghép thanh toán thành công từ payment-service với dữ liệu đơn hàng từ booking-service và danh sách sự kiện, rồi tổng hợp theo sự kiện và theo ngày cho kỳ báo cáo và kỳ liền trước cùng độ dài. */
@Service
@RequiredArgsConstructor
public class AdminEventReportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int EXPORT_LIMIT = 10_000;

    private final EventRepository events;
    private final PaymentReportClient payments;
    private final BookingInternalServiceClient bookings;

    /**
     * Dựng báo cáo: tổng hợp, mốc so sánh kỳ trước, đường thời gian theo ngày, bảng sự kiện (có lọc, sắp xếp, phân trang hoặc xuất toàn bộ) và các lựa chọn lọc. Chỉ tính đơn đã thanh toán (hoặc đã hoàn tiền), mỗi đơn một lần, theo ngày xác nhận thanh toán. Dữ liệu bất thường (trùng, không khớp, doanh thu âm) được bỏ qua và nêu trong {@code warnings}, khi đó trạng thái là {@code PARTIAL}.
     * @param authorization header Authorization chuyển tiếp sang các dịch vụ nguồn
     * @param from ngày bắt đầu kỳ báo cáo
     * @param to ngày kết thúc kỳ báo cáo (tính cả ngày này)
     * @param q từ khóa tìm theo tên sự kiện, id hoặc tên nhà tổ chức
     * @param categoryId lọc theo danh mục, có thể null
     * @param organizerId lọc theo nhà tổ chức, có thể null
     * @param status lọc theo trạng thái sự kiện, có thể null
     * @param sort {@code revenue}, {@code orders}, {@code tickets} hoặc {@code title}
     * @param page số trang, từ 1
     * @param size kích thước trang: 10, 20 hoặc 50
     * @param eventId giới hạn vào một sự kiện, có thể null
     * @param export true để lấy toàn bộ không phân trang
     * @return báo cáo đã tổng hợp
     * @throws ResponseStatusException 400 khi tham số sai, 404 khi {@code eventId} không có trong báo cáo, 422 khi xuất quá 10.000 sự kiện hoặc quá nhiều đơn trong kỳ, 503 khi một nguồn dữ liệu không khả dụng
     */
    public AdminEventReportResponse report(String authorization, LocalDate from, LocalDate to, String q,
            String categoryId, String organizerId, String status, String sort, int page, int size, String eventId,
            boolean export) {
        validate(from, to, page, size, sort);
        int span = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
        LocalDate previousFrom = from.minusDays(span);
        Instant queryFrom = previousFrom.atStartOfDay(ZONE).toInstant();
        Instant queryTo = to.plusDays(1).atStartOfDay(ZONE).toInstant();

        ReportPaymentsResponse paymentResponse;
        List<ReportBookingFact> facts;
        try {
            paymentResponse = payments.getReportPayments(authorization, queryFrom, queryTo);
            if (paymentResponse == null || paymentResponse.payments() == null)
                throw new IllegalStateException("Payment report returned no data");
            List<String> bookingIds = paymentResponse.payments().stream().map(ReportPayment::bookingId)
                    .filter(Objects::nonNull).distinct().toList();
            facts = bookingIds.isEmpty() ? List.of() : bookings.getReportFacts(authorization, bookingIds);
            if (facts == null) throw new IllegalStateException("Booking report returned no data");
        } catch (FeignException e) {
            HttpStatus sourceStatus = HttpStatus.resolve(e.status());
            if (sourceStatus == HttpStatus.FORBIDDEN || sourceStatus == HttpStatus.UNPROCESSABLE_ENTITY)
                throw new ResponseStatusException(sourceStatus, sourceStatus == HttpStatus.FORBIDDEN
                        ? "Bạn không có quyền truy cập nguồn dữ liệu báo cáo."
                        : "Có quá nhiều đơn thanh toán trong kỳ để tổng hợp. Vui lòng thu hẹp khoảng thời gian.", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Một nguồn dữ liệu báo cáo hiện không khả dụng", e);
        } catch (Exception e) {
            if (e instanceof ResponseStatusException rse) throw rse;
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Một nguồn dữ liệu báo cáo hiện không khả dụng", e);
        }

        List<Event> allEvents;
        try {
            allEvents = events.findAllForAdminReport();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Nguồn dữ liệu sự kiện hiện không khả dụng", e);
        }
        List<Event> reportableEvents = allEvents.stream().filter(AdminEventReportService::isReportable).toList();
        if (eventId != null && !eventId.isBlank() && reportableEvents.stream().noneMatch(e -> eventId.equals(e.getId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sự kiện trong báo cáo");
        Set<String> knownEventIds = allEvents.stream().map(Event::getId).collect(Collectors.toSet());
        List<Event> filtered = reportableEvents.stream().filter(e -> matches(e, q, categoryId, organizerId, status, eventId)).toList();
        Map<String, Event> scopedEvents = filtered.stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        List<String> warnings = new ArrayList<>();
        if (paymentResponse.missingTimestampCount() > 0) warnings.add("Một số thanh toán thành công chưa có thời điểm xác nhận.");

        Map<String, ReportBookingFact> factByBooking = new HashMap<>();
        boolean duplicateFacts = false;
        for (ReportBookingFact fact : facts) {
            if (fact == null || fact.bookingId() == null) continue;
            if (factByBooking.putIfAbsent(fact.bookingId(), fact) != null) duplicateFacts = true;
        }
        if (duplicateFacts) warnings.add("Đã bỏ qua dữ liệu đơn hàng bị trùng.");

        Map<String, ReportPayment> paymentByBooking = new LinkedHashMap<>();
        boolean duplicatePayments = false;
        for (ReportPayment payment : paymentResponse.payments()) {
            if (payment == null || payment.bookingId() == null || payment.confirmedAt() == null) continue;
            if (paymentByBooking.putIfAbsent(payment.bookingId(), payment) != null) duplicatePayments = true;
        }
        if (duplicatePayments) warnings.add("Đã tính mỗi đơn hàng một lần do có thanh toán bị trùng.");
        Set<String> requestedBookingIds = paymentByBooking.keySet();
        boolean orphanFacts = factByBooking.keySet().stream().anyMatch(id -> !requestedBookingIds.contains(id));
        if (orphanFacts) warnings.add("Một số dữ liệu đơn hàng không khớp với thanh toán thành công.");

        Map<String, Metrics> current = new HashMap<>(), previous = new HashMap<>();
        Map<LocalDate, Metrics> daily = new HashMap<>();
        boolean unmatched = false;
        for (ReportPayment payment : paymentByBooking.values()) {
            ReportBookingFact fact = factByBooking.get(payment.bookingId());
            if (fact == null) { unmatched = true; continue; }
            if (!isPaidBooking(fact.status())) { unmatched = true; continue; }
            Event event = scopedEvents.get(fact.eventId());
            if (event == null) {
                if (fact.eventId() == null || !knownEventIds.contains(fact.eventId()))
                    unmatched = true;
                continue;
            }
            LocalDate date = payment.confirmedAt().atZone(ZONE).toLocalDate();
            Map<String, Metrics> target = !date.isBefore(from) && !date.isAfter(to) ? current
                    : !date.isBefore(previousFrom) && date.isBefore(from) ? previous : null;
            if (target == null) continue;
            if (payment.revenue() == null || payment.revenue().signum() < 0 || fact.tickets() < 0) {
                warnings.add("Đã bỏ qua đơn hàng có doanh thu hoặc số vé không hợp lệ.");
                continue;
            }
            BigDecimal amount = payment.revenue();
            long ticketCount = fact.tickets();
            target.computeIfAbsent(event.getId(), k -> new Metrics()).add(amount, ticketCount);
            if (target == current) daily.computeIfAbsent(date, k -> new Metrics()).add(amount, ticketCount);
        }
        if (unmatched) warnings.add("Một số thanh toán chưa khớp với sự kiện hoặc đơn đã thanh toán; các đơn này chưa được tính.");

        List<AdminEventReportResponse.EventRow> allRows = filtered.stream().map(e -> row(e, current.get(e.getId())))
                .sorted(comparator(sort)).toList();
        if (export && allRows.size() > EXPORT_LIMIT)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Báo cáo vượt quá giới hạn 10.000 sự kiện. Vui lòng thu hẹp bộ lọc trước khi xuất.");

        AdminEventReportResponse.Summary currentSummary = summary(filtered, current);
        AdminEventReportResponse.Summary previousSummary = summary(filtered, previous);
        List<AdminEventReportResponse.TimelinePoint> timeline = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate date = from.plusDays(i);
            Metrics metrics = daily.getOrDefault(date, new Metrics());
            timeline.add(new AdminEventReportResponse.TimelinePoint(date, metrics.revenue, metrics.orders, metrics.tickets));
        }
        int totalPages = export ? 1 : Math.max(1, (int) Math.ceil(allRows.size() / (double) size));
        int effectivePage = export ? 1 : Math.min(page, totalPages);
        int effectiveSize = export ? Math.max(allRows.size(), 1) : size;
        int start = export ? 0 : (int) Math.min(((long) effectivePage - 1) * size, allRows.size());
        int end = export ? allRows.size() : Math.min(start + size, allRows.size());
        List<AdminEventReportResponse.EventRow> rows = allRows.subList(start, end);
        var options = options(reportableEvents);
        List<String> distinctWarnings = List.copyOf(new LinkedHashSet<>(warnings));
        return new AdminEventReportResponse(from, to, ZONE.getId(), Instant.now(), distinctWarnings.isEmpty() ? "COMPLETE" : "PARTIAL",
                distinctWarnings, currentSummary, previousSummary, List.copyOf(timeline), rows,
                effectivePage, effectiveSize, allRows.size(), totalPages, options);
    }

    /**
     * Kiểm tra tham số: khoảng ngày hợp lệ và không quá 366 ngày, trang từ 1, kích thước trang 10/20/50 và kiểu sắp xếp được hỗ trợ.
     * @throws ResponseStatusException 400 khi có tham số sai
     */
    private static void validate(LocalDate from, LocalDate to, int page, int size, String sort) {
        if (from == null || to == null || to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng ngày báo cáo không hợp lệ.");
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng báo cáo tối đa là 366 ngày.");
        if (page < 1 || !Set.of(10, 20, 50).contains(size)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trang phải bắt đầu từ 1 và kích thước trang là 10, 20 hoặc 50.");
        if (!Set.of("revenue", "orders", "tickets", "title").contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kiểu sắp xếp báo cáo không hợp lệ.");
    }

    /** Kiểm tra sự kiện có thỏa các bộ lọc không; {@code q} khớp một phần (không phân biệt hoa thường) với tên, id sự kiện hoặc tên nhà tổ chức. */
    private static boolean matches(Event e, String q, String categoryId, String organizerId, String status, String eventId) {
        if (eventId != null && !eventId.isBlank() && !eventId.equals(e.getId())) return false;
        if (categoryId != null && !categoryId.isBlank() && (e.getCategory() == null || !categoryId.equals(e.getCategory().getId()))) return false;
        if (organizerId != null && !organizerId.isBlank() && organizers(e).stream().noneMatch(o -> organizerId.equals(o.getId()))) return false;
        if (status != null && !status.isBlank() && (e.getEventStatus() == null || !status.equalsIgnoreCase(e.getEventStatus().name()))) return false;
        if (q != null && !q.isBlank()) {
            String query = q.toLowerCase(Locale.ROOT);
            return contains(e.getTitle(), query) || contains(e.getId(), query)
                    || organizers(e).stream().anyMatch(o -> contains(o.getName(), query));
        }
        return true;
    }

    /** So khớp một phần không phân biệt hoa thường; giá trị null coi như không khớp. */
    private static boolean contains(String value, String query) { return value != null && value.toLowerCase(Locale.ROOT).contains(query); }

    /** Đơn được tính doanh thu khi ở trạng thái {@code PAID} hoặc {@code REFUNDED}. */
    private static boolean isPaidBooking(String status) {
        return "PAID".equalsIgnoreCase(status) || "REFUNDED".equalsIgnoreCase(status);
    }

    /** Chỉ sự kiện đã duyệt, đang diễn ra hoặc đã kết thúc mới xuất hiện trong báo cáo. */
    private static boolean isReportable(Event event) {
        return event.getEventStatus() == EventStatus.APPROVED
                || event.getEventStatus() == EventStatus.LIVE
                || event.getEventStatus() == EventStatus.COMPLETED;
    }

    /** Dựng một dòng báo cáo cho sự kiện; sự kiện không có số liệu thì doanh thu, đơn, vé đều bằng 0. */
    private static AdminEventReportResponse.EventRow row(Event event, Metrics metrics) {
        String organizer = organizers(event).stream().map(EventOrganizer::getName).filter(Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
        EventCategory category = event.getCategory();
        return new AdminEventReportResponse.EventRow(event.getId(), event.getTitle(), organizer,
                category == null ? "" : category.getName(), event.getEventStatus() == null ? "" : event.getEventStatus().name(),
                metrics == null ? ZERO : metrics.revenue, metrics == null ? 0 : metrics.orders, metrics == null ? 0 : metrics.tickets);
    }

    /** Tạo bộ so sánh theo kiểu sắp xếp (doanh thu, đơn và vé giảm dần; tên tăng dần), khi bằng nhau thì xếp theo id sự kiện để thứ tự ổn định. */
    private static Comparator<AdminEventReportResponse.EventRow> comparator(String sort) {
        Comparator<AdminEventReportResponse.EventRow> secondary = Comparator.comparing(AdminEventReportResponse.EventRow::eventId);
        return switch (sort) {
            case "orders" -> Comparator.comparingLong(AdminEventReportResponse.EventRow::orders).reversed().thenComparing(secondary);
            case "tickets" -> Comparator.comparingLong(AdminEventReportResponse.EventRow::tickets).reversed().thenComparing(secondary);
            case "title" -> Comparator.comparing(AdminEventReportResponse.EventRow::title, String.CASE_INSENSITIVE_ORDER).thenComparing(secondary);
            default -> Comparator.comparing(AdminEventReportResponse.EventRow::revenue).reversed().thenComparing(secondary);
        };
    }

    /** Cộng dồn doanh thu, số đơn, số vé và số sự kiện có bán được vé trên các sự kiện đang xét. */
    private static AdminEventReportResponse.Summary summary(List<Event> events, Map<String, Metrics> metrics) {
        BigDecimal revenue = ZERO; long orders = 0, tickets = 0, eventsWithSales = 0;
        for (Event event : events) {
            Metrics m = metrics.get(event.getId());
            if (m != null) { revenue = revenue.add(m.revenue); orders += m.orders; tickets += m.tickets; if (m.orders > 0) eventsWithSales++; }
        }
        return new AdminEventReportResponse.Summary(revenue, orders, tickets, eventsWithSales);
    }

    /** Dựng danh sách lựa chọn cho bộ lọc (danh mục, nhà tổ chức, trạng thái) từ các sự kiện có thể báo cáo. */
    private static AdminEventReportResponse.Options options(List<Event> events) {
        Map<String, String> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER), organizers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> statuses = new TreeSet<>();
        for (Event e : events) {
            if (e.getCategory() != null) categories.put(e.getCategory().getId(), e.getCategory().getName());
            organizers(e).forEach(o -> organizers.put(o.getId(), o.getName()));
            if (e.getEventStatus() != null) statuses.add(e.getEventStatus().name());
        }
        return new AdminEventReportResponse.Options(categories.entrySet().stream().map(e -> new AdminEventReportResponse.Option(e.getKey(), e.getValue())).toList(),
                organizers.entrySet().stream().map(e -> new AdminEventReportResponse.Option(e.getKey(), e.getValue())).toList(), List.copyOf(statuses));
    }

    /** Bộ cộng dồn số liệu: doanh thu, số đơn và số vé. */
    private static final class Metrics {
        BigDecimal revenue = ZERO; long orders; long tickets;
        /** Cộng thêm một đơn: cộng doanh thu, tăng số đơn lên 1 và cộng số vé. */
        void add(BigDecimal amount, long ticketCount) { revenue = revenue.add(amount); orders++; tickets += ticketCount; }
    }

    /** Danh sách nhà tổ chức của sự kiện, rỗng khi chưa có. */
    private static List<EventOrganizer> organizers(Event event) {
        return event.getOrganizers() == null ? List.of() : event.getOrganizers();
    }
}
