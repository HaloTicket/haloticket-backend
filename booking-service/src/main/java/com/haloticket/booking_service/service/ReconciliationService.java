package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.config.ReconciliationProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Đối soát cho admin: các khoản thanh toán cần người xử lý, đơn đã thanh toán nhưng thiếu vé và tồn kho không khớp.
 * Hai mục sau hỏi ticket-service; khi nó không trả lời thì kết quả là HTTP 502 chứ không bao giờ là danh sách rỗng.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    public static final String SOURCE_UNAVAILABLE = "RECONCILIATION_SOURCE_UNAVAILABLE";
    static final int ISSUED_COUNTS_MAX = 200;

    /** Sáu trường cuối (khách, phương thức thanh toán, sự kiện) do ReconciliationDetails điền và là null khi tra cứu lỗi. */
    public record ExceptionItem(String id, String bookingId, String bookingCode, String paymentId, String kind, Double amount,
                                String provider, String status, String detail, LocalDateTime createdAt,
                                LocalDateTime resolvedAt, String resolvedBy, String resolutionNote,
                                String customerName, String customerEmail, String customerPhone, String paymentMethod,
                                String eventId, String eventTitle) {
        /** Tạo dòng chưa có thông tin khách, phương thức thanh toán và sự kiện (sẽ bổ sung sau bằng {@code withDetails}). */
        public ExceptionItem(String id, String bookingId, String bookingCode, String paymentId, String kind, Double amount,
                             String provider, String status, String detail, LocalDateTime createdAt,
                             LocalDateTime resolvedAt, String resolvedBy, String resolutionNote) {
            this(id, bookingId, bookingCode, paymentId, kind, amount, provider, status, detail, createdAt, resolvedAt, resolvedBy,
                    resolutionNote, null, null, null, null, null, null);
        }

        /** Trả bản sao đã điền thông tin khách, phương thức thanh toán và sự kiện. */
        public ExceptionItem withDetails(String name, String email, String phone, String method, String eventId, String eventTitle) {
            return new ExceptionItem(id, bookingId, bookingCode, paymentId, kind, amount, provider, status, detail, createdAt,
                    resolvedAt, resolvedBy, resolutionNote, name, email, phone, method, eventId, eventTitle);
        }
    }

    /** Một trang ngoại lệ thanh toán: các dòng, tổng số, số trang và kích thước trang. */
    public record ExceptionPage(List<ExceptionItem> items, long total, int page, int size) {}

    /** Một đơn đã thanh toán nhưng thiếu vé: số vé cần có, số đã phát hành và số phút kể từ lúc thanh toán. */
    public record PaidWithoutTicketsItem(String bookingId, String bookingCode, LocalDateTime paidAt, long expectedTickets,
                                         long issuedTickets, long minutesSincePaid,
                                         String customerName, String customerEmail, String customerPhone, String paymentMethod,
                                         Double amount, String eventId, String eventTitle) {
        /** Tạo dòng chưa có thông tin khách, phương thức thanh toán, số tiền và sự kiện. */
        public PaidWithoutTicketsItem(String bookingId, String bookingCode, LocalDateTime paidAt, long expectedTickets,
                                      long issuedTickets, long minutesSincePaid) {
            this(bookingId, bookingCode, paidAt, expectedTickets, issuedTickets, minutesSincePaid, null, null, null, null, null, null, null);
        }

        /** Trả bản sao đã điền thông tin khách, phương thức thanh toán, số tiền và sự kiện. */
        public PaidWithoutTicketsItem withDetails(String name, String email, String phone, String method, Double amount,
                                                  String eventId, String eventTitle) {
            return new PaidWithoutTicketsItem(bookingId, bookingCode, paidAt, expectedTickets, issuedTickets, minutesSincePaid,
                    name, email, phone, method, amount, eventId, eventTitle);
        }
    }

    /** Danh sách đơn thiếu vé, tổng số và cờ {@code truncated} khi còn đơn chưa quét hết do vượt số lô tối đa. */
    public record PaidWithoutTickets(List<PaidWithoutTicketsItem> items, int total, boolean truncated) {}

    /** Danh sách loại vé có tồn kho lệch và tổng số. */
    public record StockDiscrepancies(List<Map<String, Object>> items, int total) {}

    private final PaymentExceptionRepository exceptionRepository;
    private final BookingRepository bookingRepository;
    private final TicketStockClient ticketStockClient;
    private final GaReservationProperties ticketProperties;
    private final ReconciliationProperties properties;

    /** Tên khách, thanh toán và sự kiện cho các dòng. Tùy chọn: không có thì các dòng chỉ mang mã và số tiền. */
    @Autowired(required = false)
    private ReconciliationDetails details;

    /** Tổng hợp số liệu đối soát; một số đếm là null khi không đọc được nguồn của nó. */
    public Map<String, Object> summary() {
        autoResolveIssued();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openExceptions", exceptionRepository.countByStatus(PaymentExceptionStatus.OPEN));
        out.put("paidWithoutTickets", countOrNull(() -> paidWithoutTickets().total()));
        out.put("stockDiscrepancies", countOrNull(() -> stockDiscrepancies().total()));
        return out;
    }

    /** Chạy nguồn đếm, trả null nếu nguồn ném {@code ResponseStatusException} (không đọc được). */
    private Integer countOrNull(Supplier<Integer> source) {
        try {
            return source.get();
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    /**
     * Ngoại lệ ISSUANCE_FAILED đang mở mà đơn nay đã đủ vé (admin, khách hoặc bộ lập lịch phát lại, hoặc sau khi sửa) được đóng ở đây
     * với người xử lý là "system" để không ai phải đánh dấu tay. Cố gắng hết sức: khi ticket-service không trả lời thì không đóng gì.
     * @return số ngoại lệ đã tự đóng
     */
    public int autoResolveIssued() {
        try {
            List<PaymentException> open = exceptionRepository.findTop200ByKindAndStatus(
                    com.haloticket.booking_service.enums.PaymentExceptionKind.ISSUANCE_FAILED, PaymentExceptionStatus.OPEN);
            if (open.isEmpty()) return 0;
            List<String> ids = open.stream().map(PaymentException::getBookingId).distinct().toList();
            Map<String, Long> expected = bookingRepository.findReportFactsByBookingIds(ids).stream()
                    .collect(Collectors.toMap(BookingRepository.ReportFact::getBookingId,
                            f -> f.getTickets() == null ? 0L : f.getTickets(), (a, b) -> a));
            Map<String, Integer> issued = new HashMap<>();
            for (int i = 0; i < ids.size(); i += ISSUED_COUNTS_MAX) {
                issued.putAll(issuedCounts(ids.subList(i, Math.min(ids.size(), i + ISSUED_COUNTS_MAX))));
            }
            List<PaymentException> done = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (PaymentException e : open) {
                long want = expected.getOrDefault(e.getBookingId(), 0L);
                if (want > 0 && issued.getOrDefault(e.getBookingId(), 0) >= want) {
                    e.setStatus(PaymentExceptionStatus.RESOLVED);
                    e.setResolvedAt(now);
                    e.setResolvedBy("system");
                    e.setResolutionNote("Vé đã được phát hành đầy đủ, hệ thống tự ghi nhận.");
                    done.add(e);
                }
            }
            if (!done.isEmpty()) {
                exceptionRepository.saveAll(done);
                log.info("[PAYMENT_EXCEPTION] auto-resolved {} issuance exception(s) whose tickets now exist", done.size());
            }
            return done.size();
        } catch (RuntimeException e) {
            log.warn("[PAYMENT_EXCEPTION] auto-resolve skipped: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Sửa số tồn kho còn lại của một loại vé ở ticket-service; các từ chối (404/409/422) được chuyển tiếp nguyên vẹn.
     * @param ticketTypeId mã loại vé
     * @return dòng tồn kho sau khi sửa, kèm tên sự kiện nếu tra được
     * @throws ResponseStatusException HTTP 404/409/422 khi ticket-service từ chối, HTTP 502 khi không gọi được
     */
    public Map<String, Object> fixStock(String ticketTypeId) {
        Map<String, Object> row;
        try {
            row = ticketStockClient.fixStock(token(), ticketTypeId);
        } catch (feign.FeignException e) {
            int code = e.status();
            if (code == 404 || code == 409 || code == 422) {
                throw new ResponseStatusException(HttpStatus.valueOf(code), "STOCK_FIX_REFUSED_" + code, e);
            }
            throw unavailable("stock fix failed with status " + code, e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e.getClass().getSimpleName(), e);
        }
        if (row == null) throw unavailable("stock fix returned no body", null);
        log.info("[RECONCILIATION] stock fixed ticketTypeId={}", ticketTypeId);
        List<Map<String, Object>> single = List.of(row);
        return (details == null ? single : details.stockRows(single)).get(0);
    }

    /**
     * Liệt kê ngoại lệ thanh toán mới nhất trước, có lọc theo trạng thái; trước đó tự đóng các ngoại lệ phát vé đã đủ vé.
     * @param status trạng thái cần lọc, null để lấy tất cả
     * @param page số trang bắt đầu từ 0
     * @param size số dòng mỗi trang
     * @return trang kết quả kèm thông tin khách và sự kiện
     */
    public ExceptionPage exceptions(PaymentExceptionStatus status, int page, int size) {
        autoResolveIssued();
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<PaymentException> result = status == null ? exceptionRepository.findAll(request)
                : exceptionRepository.findByStatus(status, request);
        Map<String, String> codes = bookingRepository.findAllById(
                        result.getContent().stream().map(PaymentException::getBookingId).distinct().toList())
                .stream().collect(Collectors.toMap(Booking::getId, Booking::getBookingCode, (a, b) -> a));
        List<ExceptionItem> items = result.getContent().stream().map(e -> toItem(e, codes.get(e.getBookingId()))).toList();
        return new ExceptionPage(details == null ? items : details.exceptionItems(items), result.getTotalElements(), page, size);
    }

    /**
     * Đánh dấu một ngoại lệ đã xử lý (có hoặc không hoàn tiền thủ công). Không động tới đơn.
     * @param id mã ngoại lệ
     * @param note ghi chú xử lý, từ 3 đến 500 ký tự
     * @param refundedManually true nếu admin đã hoàn tiền thủ công
     * @param resolvedBy người xử lý
     * @return ngoại lệ sau khi cập nhật
     * @throws ResponseStatusException HTTP 422 khi ghi chú không hợp lệ, 404 khi không có ngoại lệ, 409 khi đã được xử lý
     */
    @Transactional
    public ExceptionItem resolve(String id, String note, boolean refundedManually, String resolvedBy) {
        String cleaned = note == null ? "" : note.trim();
        if (cleaned.length() < 3 || cleaned.length() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESOLUTION_NOTE");
        }
        PaymentException exception = exceptionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_EXCEPTION_NOT_FOUND"));
        if (exception.getStatus() != PaymentExceptionStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_EXCEPTION_ALREADY_RESOLVED");
        }
        exception.setStatus(refundedManually ? PaymentExceptionStatus.REFUNDED_MANUALLY : PaymentExceptionStatus.RESOLVED);
        exception.setResolvedAt(LocalDateTime.now());
        exception.setResolvedBy(PaymentExceptionService.truncate(resolvedBy, 64));
        exception.setResolutionNote(cleaned);
        exceptionRepository.save(exception);
        log.info("[PAYMENT_EXCEPTION] resolved id={} status={} by={}", id, exception.getStatus(), resolvedBy);
        String code = bookingRepository.findById(exception.getBookingId()).map(Booking::getBookingCode).orElse(null);
        return toItem(exception, code);
    }

    /** Chuyển thực thể ngoại lệ sang dòng trả về, chưa kèm thông tin khách. */
    private static ExceptionItem toItem(PaymentException e, String bookingCode) {
        return new ExceptionItem(e.getId(), e.getBookingId(), bookingCode, e.getPaymentId(), e.getKind().name(), e.getAmount(),
                e.getProvider(), e.getStatus().name(), e.getDetail(), e.getCreatedAt(), e.getResolvedAt(), e.getResolvedBy(),
                e.getResolutionNote());
    }

    /**
     * Cả đơn PAID và CONFIRMED đều đáng lẽ có vé (PAID = đã áp dụng thanh toán và đã gửi BOOKING_CONFIRMED; CONFIRMED được để dành
     * và hiện chưa có mã nào đặt). Kiểm tra các đơn thanh toán trong khoảng từ lookbackDays ngày trước đến paidGraceMinutes phút trước,
     * cũ nhất trước, mỗi lô batchSize đơn và tối đa maxBatches lô, rồi giữ lại những đơn có ít vé hơn dự kiến.
     * @return danh sách đơn thiếu vé; {@code truncated} bằng true khi chưa quét hết
     */
    public PaidWithoutTickets paidWithoutTickets() {
        int batch = Math.max(1, Math.min(properties.getBatchSize(), ISSUED_COUNTS_MAX));
        int maxBatches = Math.max(1, properties.getMaxBatches());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime to = now.minusMinutes(Math.max(0, properties.getPaidGraceMinutes()));
        LocalDateTime from = now.minusDays(Math.max(1, properties.getLookbackDays()));
        List<PaidWithoutTicketsItem> found = new ArrayList<>();
        boolean truncated = false;
        for (int pageIndex = 0; pageIndex < maxBatches; pageIndex++) {
            List<BookingRepository.PaidCandidate> candidates =
                    bookingRepository.findPaidCandidates(from, to, PageRequest.of(pageIndex, batch));
            if (candidates.isEmpty()) break;
            Map<String, Integer> issued = issuedCounts(
                    candidates.stream().map(BookingRepository.PaidCandidate::getBookingId).toList());
            for (BookingRepository.PaidCandidate c : candidates) {
                long expected = c.getExpectedTickets() == null ? 0 : c.getExpectedTickets();
                long have = issued.getOrDefault(c.getBookingId(), 0);
                if (expected > 0 && have < expected) {
                    found.add(new PaidWithoutTicketsItem(c.getBookingId(), c.getBookingCode(), c.getPaidAt(), expected, have,
                            c.getPaidAt() == null ? 0 : Duration.between(c.getPaidAt(), now).toMinutes()));
                }
            }
            if (candidates.size() < batch) break;
            if (pageIndex == maxBatches - 1) truncated = true;
        }
        return new PaidWithoutTickets(details == null ? found : details.paidItems(found), found.size(), truncated);
    }

    /**
     * Lấy các loại vé có tồn kho lệch từ ticket-service.
     * @return danh sách dòng lệch kèm tên sự kiện
     * @throws ResponseStatusException HTTP 502 khi ticket-service không trả lời hoặc không có dữ liệu
     */
    public StockDiscrepancies stockDiscrepancies() {
        List<Map<String, Object>> rows = fromTicketService(
                () -> ticketStockClient.stockReconciliation(token(), Math.max(1, properties.getStockLimit())));
        if (rows == null) throw unavailable("stock-reconciliation returned no body", null);
        return new StockDiscrepancies(details == null ? rows : details.stockRows(rows), rows.size());
    }

    /**
     * Hỏi ticket-service số vé đã phát hành cho các đơn.
     * @param bookingIds mã các đơn
     * @return bản đồ mã đơn sang số vé đã phát hành
     * @throws ResponseStatusException HTTP 502 khi không lấy được số liệu
     */
    private Map<String, Integer> issuedCounts(List<String> bookingIds) {
        Map<String, Map<String, Integer>> body = fromTicketService(
                () -> ticketStockClient.issuedCounts(token(), Map.of("bookingIds", bookingIds)));
        // phản hồi thiếu số đếm không được hiểu thành "không ai có vé"
        if (body == null || body.get("counts") == null) throw unavailable("issued-counts returned no counts", null);
        return new HashMap<>(body.get("counts"));
    }

    /** Lấy token nội bộ để gọi ticket-service; ném HTTP 502 nếu chưa cấu hình. */
    private String token() {
        String token = ticketProperties.getInternalToken();
        if (token == null || token.isBlank()) throw unavailable("internal token is not configured", null);
        return token;
    }

    /**
     * Chạy một lời gọi ticket-service; mọi lỗi runtime được đổi thành HTTP 502.
     * @param call lời gọi cần chạy
     * @return kết quả của lời gọi
     */
    private <T> T fromTicketService(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e.getClass().getSimpleName(), e);
        }
    }

    /** Ghi log lỗi và tạo ngoại lệ HTTP 502 cho trường hợp nguồn ticket-service không dùng được. */
    private ResponseStatusException unavailable(String why, Throwable cause) {
        log.error("[RECONCILIATION] ticket-service source unavailable: {}", why);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, SOURCE_UNAVAILABLE, cause);
    }
}
