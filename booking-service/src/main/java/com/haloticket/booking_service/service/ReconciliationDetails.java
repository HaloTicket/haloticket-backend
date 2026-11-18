package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.client.UserServiceClient;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bổ sung cho các dòng của trang đối soát admin: ai đã trả, trả bằng cách nào và cho sự kiện nào. Mọi lần tra cứu đều theo kiểu
 * cố gắng hết sức: user-service, event-service hoặc payment-service không trả lời thì trường đó để null và không làm hỏng trang (mã đơn và số tiền
 * vẫn đủ nhận diện dòng). Mỗi trang tra cứu tối đa LOOKUP_MAX khách và sự kiện khác nhau.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationDetails {

    static final int LOOKUP_MAX = 60;

    private final BookingRepository bookings;
    private final UserServiceClient users;
    private final EventServiceClient events;
    private final PaymentServiceClient payments;

    /** Thông tin liên hệ của khách (tên, email, số điện thoại); {@code NONE} khi không có. */
    record Customer(String name, String email, String phone) {
        static final Customer NONE = new Customer(null, null, null);
    }

    /** Cache theo từng lần gọi để mỗi khách hoặc sự kiện chỉ được tra một lần dù có nhiều dòng nhắc tới. */
    private final class Lookups {
        private final Map<String, Booking> byBooking;
        private final Map<String, Customer> customers = new HashMap<>();
        private final Map<String, String> titles = new HashMap<>();
        private final Map<String, String> methods;

        /**
         * Nạp sẵn các đơn và phương thức thanh toán của danh sách mã đơn.
         * @param bookingIds các mã đơn cần tra cứu, trùng được gộp
         */
        Lookups(Collection<String> bookingIds) {
            this.byBooking = bookingIds.isEmpty() ? Map.of() : bookings.findAllById(bookingIds.stream().distinct().toList()).stream()
                    .collect(Collectors.toMap(Booking::getId, Function.identity(), (a, b) -> a));
            this.methods = methodsOf(byBooking.keySet());
        }

        /**
         * Lấy đơn đã nạp theo id.
         * @param id mã đơn, có thể null
         * @return đơn, hoặc null nếu không có
         */
        Booking booking(String id) {
            return id == null ? null : byBooking.get(id);
        }

        /**
         * Lấy phương thức thanh toán của đơn.
         * @param bookingId mã đơn, có thể null
         * @return phương thức thanh toán, hoặc null nếu không biết
         */
        String method(String bookingId) {
            return bookingId == null ? null : methods.get(bookingId);
        }

        /**
         * Lấy thông tin khách của đơn: ưu tiên tên và email từ user-service (nếu tra được), kèm số điện thoại; có giới hạn số lần tra.
         * @param booking đơn, có thể null
         * @return thông tin khách, {@code Customer.NONE} nếu không có đơn
         */
        Customer customer(Booking booking) {
            if (booking == null) return Customer.NONE;
            String key = booking.getCustomerId() == null ? "booking:" + booking.getId() : booking.getCustomerId();
            return customers.computeIfAbsent(key, k -> {
                String name = blankToNull(booking.getCustomerName());
                String email = blankToNull(booking.getCustomerEmail());
                String phone = null;
                if (booking.getCustomerId() != null && customers.size() < LOOKUP_MAX) {
                    try {
                        UserServiceClient.InternalUser user = users.getUserById(booking.getCustomerId());
                        if (user != null) {
                            name = firstNonBlank(user.fullName(), name);
                            email = firstNonBlank(user.email(), email);
                            phone = blankToNull(user.phoneNumber());
                        }
                    } catch (RuntimeException e) {
                        log.warn("[RECONCILIATION] customer lookup failed: {}", e.getClass().getSimpleName());
                    }
                }
                return new Customer(name, email, phone);
            });
        }

        /**
         * Lấy tên sự kiện từ event-service, có cache và giới hạn số lần tra.
         * @param eventId mã sự kiện
         * @return tên sự kiện, hoặc null nếu không tra được
         */
        String eventTitle(String eventId) {
            if (eventId == null || eventId.isBlank()) return null;
            if (titles.containsKey(eventId)) return titles.get(eventId);
            String title = null;
            if (titles.size() < LOOKUP_MAX) {
                try {
                    var event = events.getEventById(eventId);
                    title = event == null ? null : blankToNull(event.getTitle());
                } catch (RuntimeException e) {
                    log.warn("[RECONCILIATION] event lookup failed: {}", e.getClass().getSimpleName());
                }
            }
            titles.put(eventId, title);
            return title;
        }
    }

    /**
     * Hỏi payment-service phương thức thanh toán của các đơn; lỗi thì trả bản đồ rỗng.
     * @param bookingIds mã các đơn
     * @return bản đồ mã đơn sang phương thức thanh toán
     */
    private Map<String, String> methodsOf(Collection<String> bookingIds) {
        if (bookingIds.isEmpty()) return Map.of();
        try {
            Map<String, String> answer = payments.getPaymentMethodsByBookingIds(new ArrayList<>(bookingIds));
            return answer == null ? Map.of() : answer;
        } catch (RuntimeException e) {
            log.warn("[RECONCILIATION] payment methods lookup failed: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * Bổ sung khách, phương thức thanh toán và sự kiện vào các dòng ngoại lệ thanh toán.
     * @param items các dòng ngoại lệ
     * @return các dòng đã bổ sung thông tin
     */
    public List<ReconciliationService.ExceptionItem> exceptionItems(List<ReconciliationService.ExceptionItem> items) {
        Lookups lookups = new Lookups(items.stream().map(ReconciliationService.ExceptionItem::bookingId).filter(java.util.Objects::nonNull).toList());
        List<ReconciliationService.ExceptionItem> out = new ArrayList<>();
        for (ReconciliationService.ExceptionItem item : items) {
            Booking booking = lookups.booking(item.bookingId());
            Customer customer = lookups.customer(booking);
            String eventId = booking == null ? null : booking.getEventId();
            String method = firstNonBlank(lookups.method(item.bookingId()), item.provider());
            out.add(item.withDetails(customer.name(), customer.email(), customer.phone(), method, eventId, lookups.eventTitle(eventId)));
        }
        return out;
    }

    /**
     * Bổ sung khách, phương thức thanh toán, số tiền và sự kiện vào các dòng đơn đã thanh toán nhưng thiếu vé.
     * @param items các dòng đơn đã thanh toán thiếu vé
     * @return các dòng đã bổ sung thông tin
     */
    public List<ReconciliationService.PaidWithoutTicketsItem> paidItems(List<ReconciliationService.PaidWithoutTicketsItem> items) {
        Lookups lookups = new Lookups(items.stream().map(ReconciliationService.PaidWithoutTicketsItem::bookingId).filter(java.util.Objects::nonNull).toList());
        List<ReconciliationService.PaidWithoutTicketsItem> out = new ArrayList<>();
        for (ReconciliationService.PaidWithoutTicketsItem item : items) {
            Booking booking = lookups.booking(item.bookingId());
            Customer customer = lookups.customer(booking);
            String eventId = booking == null ? null : booking.getEventId();
            out.add(item.withDetails(customer.name(), customer.email(), customer.phone(), lookups.method(item.bookingId()),
                    booking == null ? null : booking.getTotalAmount(), eventId, lookups.eventTitle(eventId)));
        }
        return out;
    }

    /** Các loại vé lệch tồn kho đến từ ticket-service chỉ có mã sự kiện: bổ sung thêm tên sự kiện. */
    public List<Map<String, Object>> stockRows(List<Map<String, Object>> rows) {
        Lookups lookups = new Lookups(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Object eventId = row.get("eventId");
            copy.put("eventTitle", eventId == null ? null : lookups.eventTitle(String.valueOf(eventId)));
            out.add(copy);
        }
        return out;
    }

    /** Chuỗi null hoặc trống thì thành null, ngược lại cắt khoảng trắng hai đầu. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Trả giá trị không trống đầu tiên trong hai chuỗi, hoặc null nếu cả hai đều trống. */
    private static String firstNonBlank(String first, String second) {
        String a = blankToNull(first);
        return a != null ? a : blankToNull(second);
    }
}
