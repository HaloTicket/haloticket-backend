package com.haloticket.booking_service.config;

import com.haloticket.booking_service.service.SeatWebsocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Khi khóa giữ ghế {@code seat:hold:*} trong Redis hết hạn, phát qua WebSocket rằng ghế đã trống trở lại.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisKeyExpirationListener implements MessageListener {

    private final SeatWebsocketService websocketService;

    /**
     * Xử lý thông báo khóa hết hạn: chỉ quan tâm khóa {@code seat:hold:event:{eventId}:{seatId}} hoặc {@code seat:hold:showtime:{showtimeId}:{seatId}},
     * rồi phát trạng thái {@code AVAILABLE} của ghế.
     *
     * @param message thông báo chứa tên khóa đã hết hạn
     * @param pattern mẫu đăng ký (không dùng)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();

        // định dạng khóa: seat:hold:event:{eventId}:{seatId} hoặc seat:hold:showtime:{showtimeId}:{seatId}
        if (expiredKey.startsWith("seat:hold:")) {
            log.info("Seat hold expired: {}", expiredKey);
            String[] parts = expiredKey.split(":");
            if (parts.length >= 5) {
                String type = parts[2]; // "event" hoặc "showtime"
                String id = parts[3];
                String seatId = parts[4];
                
                String showtimeId = "showtime".equals(type) ? id : null;
                String broadcastTargetEventId = "event".equals(type) ? id : "showtime-" + id; 

                // phát thông báo ghế đã trống
                websocketService.broadcastSeatStatus(broadcastTargetEventId, showtimeId, seatId, "AVAILABLE");
            }
        }
    }
}
