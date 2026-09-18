package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.SeatStatusMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatWebsocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * broadcasts a seat status change to all clients subscribed to a specific event.
     * clients should subscribe to /topic/seats/{eventId}
     */
    public void broadcastSeatStatus(String eventId, String seatId, String status) {
        String destination = "/topic/seats/" + eventId;
        SeatStatusMessage message = SeatStatusMessage.builder()
                .eventId(eventId)
                .seatId(seatId)
                .status(status)
                .build();
                
        log.info("Broadcasting seat status to {}: {}", destination, message);
        messagingTemplate.convertAndSend(destination, message);
    }
}
