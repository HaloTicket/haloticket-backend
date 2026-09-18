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

        // key format: seat_hold:{eventId}:{seatId}
        if (expiredKey.startsWith("seat_hold:")) {
            log.info("Seat hold expired: {}", expiredKey);
            String[] parts = expiredKey.split(":");
            if (parts.length >= 3) {
                String eventId = parts[1];
                String seatId = parts[2];
                // broadcast seat available
                websocketService.broadcastSeatStatus(eventId, seatId, "AVAILABLE");
            }
        }
    }
}
