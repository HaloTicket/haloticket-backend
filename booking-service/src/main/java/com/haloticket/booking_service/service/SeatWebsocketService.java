package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.SeatStatusMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** Đẩy thay đổi trạng thái ghế tới các client qua WebSocket (STOMP). */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatWebsocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Phát thay đổi trạng thái ghế cho mọi client đang theo dõi một sự kiện.
     * Client đăng ký kênh {@code /topic/seats/{eventId}}.
     * @param eventId mã sự kiện
     * @param showtimeId mã suất chiếu
     * @param seatId mã ghế
     * @param status trạng thái mới, ví dụ HELD, SOLD, AVAILABLE
     */
    public void broadcastSeatStatus(String eventId, String showtimeId, String seatId, String status) {
        String destination = "/topic/seats/" + eventId;
        SeatStatusMessage message = SeatStatusMessage.builder()
                .eventId(eventId)
                .showtimeId(showtimeId)
                .seatId(seatId)
                .status(status)
                .build();
                
        log.info("Broadcasting seat status to {}: {}", destination, message);
        messagingTemplate.convertAndSend(destination, message);
    }
}
