package com.haloticket.booking_service.config;

import com.haloticket.booking_service.service.SeatWebsocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisKeyExpirationListener implements MessageListener {

    private final SeatWebsocketService websocketService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();

        // key format: seat:hold:event:{eventId}:{seatId} or seat:hold:showtime:{showtimeId}:{seatId}
        if (expiredKey.startsWith("seat:hold:")) {
            log.info("Seat hold expired: {}", expiredKey);
            String[] parts = expiredKey.split(":");
            if (parts.length >= 5) {
                String type = parts[2]; // "event" or "showtime"
                String id = parts[3];
                String seatId = parts[4];
                
                String showtimeId = "showtime".equals(type) ? id : null;
                String broadcastTargetEventId = "event".equals(type) ? id : "showtime-" + id; 

                // broadcast seat available
                websocketService.broadcastSeatStatus(broadcastTargetEventId, showtimeId, seatId, "AVAILABLE");
            }
        }
    }
}
