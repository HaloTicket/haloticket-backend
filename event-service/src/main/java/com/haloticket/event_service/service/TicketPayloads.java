package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.EventCreateRequest.TicketCreateRequest;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.service.TicketSyncService.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dựng dữ liệu gửi sang ticket-service. Kết quả có tính xác định: cùng sự kiện và request luôn cho cùng payload, nên lưu hai
 * lần (hoặc đồng bộ lại) gửi đúng cùng một nội dung. Số vé phát hành được mang trong {@code initialQuantity}; khi request chỉ
 * có {@code totalQuantity} thì dùng giá trị đó (cả hai trường đều được gán, trường cũ giữ lại để tương thích).
 */
public final class TicketPayloads {
    /** Lớp tiện ích, không cho tạo đối tượng. */
    private TicketPayloads() {}

    /** Số vé phát hành: {@code initialQuantity}, nếu không có thì lấy {@code totalQuantity} (trường cũ). */
    public static Integer issued(TicketCreateRequest t) {
        return t.getInitialQuantity() != null ? t.getInitialQuantity() : t.getTotalQuantity();
    }

    /** Bản sao đầy đủ (giữ nguyên {@code id} và {@code showtimeId}) với số lượng đã chuẩn hóa. */
    public static TicketCreateRequest copy(TicketCreateRequest t) {
        TicketCreateRequest c = new TicketCreateRequest();
        c.setId(t.getId());
        c.setShowtimeId(t.getShowtimeId());
        fill(c, t);
        return c;
    }

    /** Bản sao cho một suất diễn: chỉ giữ {@code id} khi vé vốn đã thuộc đúng suất đó, ngược lại để ticket-service tạo vé mới. */
    public static TicketCreateRequest cloneFor(TicketCreateRequest t, String showtimeId) {
        TicketCreateRequest c = new TicketCreateRequest();
        if (showtimeId != null && showtimeId.equals(t.getShowtimeId())) c.setId(t.getId());
        c.setShowtimeId(showtimeId);
        fill(c, t);
        return c;
    }

    /** Chép các trường nghiệp vụ của vé nguồn sang vé đích và chuẩn hóa hai trường số lượng về cùng một giá trị. */
    private static void fill(TicketCreateRequest c, TicketCreateRequest t) {
        Integer issued = issued(t);
        c.setName(t.getName());
        c.setPrice(t.getPrice());
        c.setIsFree(t.getIsFree());
        c.setInitialQuantity(issued);
        c.setTotalQuantity(issued);
        c.setMinPerOrder(t.getMinPerOrder());
        c.setMaxPerOrder(t.getMaxPerOrder());
        c.setDescription(t.getDescription());
    }

    /**
     * Chia vé thành các lô đồng bộ. Sự kiện có suất diễn chỉ được đồng bộ theo từng suất (không bao giờ ở cấp sự kiện): dùng
     * vé riêng của suất nếu có, không thì nhân bản vé cấp sự kiện cho suất đó. Sự kiện không có suất diễn đồng bộ một lô với
     * {@code showtimeId = null}.
     *
     * @param showtimes các suất diễn của sự kiện
     * @param eventLevel vé khai báo ở cấp sự kiện
     * @param ownTickets hàm trả về vé riêng của một suất diễn
     * @return danh sách lô, mỗi lô tương ứng một lần gọi ticket-service
     */
    public static List<Batch> batches(List<EventShowtime> showtimes, List<TicketCreateRequest> eventLevel,
                                      Function<EventShowtime, List<TicketCreateRequest>> ownTickets) {
        List<Batch> batches = new ArrayList<>();
        boolean hasEventLevel = eventLevel != null && !eventLevel.isEmpty();
        if (showtimes == null || showtimes.isEmpty()) {
            if (hasEventLevel) {
                List<TicketCreateRequest> copies = new ArrayList<>();
                for (TicketCreateRequest t : eventLevel) copies.add(copy(t));
                batches.add(new Batch(null, copies));
            }
            return batches;
        }
        for (EventShowtime st : showtimes) {
            List<TicketCreateRequest> source = ownTickets.apply(st);
            if (source == null || source.isEmpty()) source = hasEventLevel ? eventLevel : null;
            if (source == null) continue;
            List<TicketCreateRequest> clones = new ArrayList<>();
            for (TicketCreateRequest t : source) clones.add(cloneFor(t, st.getId()));
            batches.add(new Batch(st.getId(), clones));
        }
        return batches;
    }
}
