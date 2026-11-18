package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Client Feign gọi ticket-service để đồng bộ và đọc loại vé, sơ đồ ghế của sự kiện. Mã 422 được dịch thành {@code TicketSyncRejectedException} qua {@code TicketServiceErrorConfig}. */
@FeignClient(name = "ticket-service", path = "/api/tickets", configuration = {InternalTokenFeignConfig.class, TicketServiceErrorConfig.class})
public interface TicketServiceClient {

    /**
     * Đẩy danh sách loại vé của sự kiện (hoặc của một suất diễn) sang ticket-service để đồng bộ.
     * @param eventId mã sự kiện
     * @param showtimeId mã suất diễn; bỏ trống thì đồng bộ ở cấp sự kiện
     * @param requests danh sách loại vé cần đồng bộ
     * @return phản hồi của ticket-service
     */
    @PostMapping("/types/sync/{eventId}")
    Object syncTicketTypesForEvent(@PathVariable("eventId") String eventId,
                                   @RequestParam(value = "showtimeId", required = false) String showtimeId,
                                   @RequestBody List<EventCreateRequest.TicketCreateRequest> requests);

    /**
     * Lấy các loại vé của sự kiện.
     * @param eventId mã sự kiện
     * @return danh sách loại vé do ticket-service trả về
     */
    @GetMapping("/types/event/{eventId}")
    Object getTicketTypesByEventId(@PathVariable("eventId") String eventId);

    /**
     * Lấy toàn bộ loại vé của sự kiện (đường dẫn {@code /all}).
     * @param eventId mã sự kiện
     * @return danh sách loại vé do ticket-service trả về
     */
    @GetMapping("/types/event/{eventId}/all")
    Object getAllTicketTypesByEventId(@PathVariable("eventId") String eventId);

    /**
     * Lấy sơ đồ ghế của sự kiện.
     * @param eventId mã sự kiện
     * @return sơ đồ ghế do ticket-service trả về
     */
    @GetMapping("/seat-layouts/event/{eventId}")
    Object getSeatLayoutByEventId(@PathVariable("eventId") String eventId);
}
