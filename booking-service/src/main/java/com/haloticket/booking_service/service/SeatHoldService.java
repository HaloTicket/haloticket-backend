package com.haloticket.booking_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    private final RedissonClient redissonClient;
    private final SeatWebsocketService websocketService;

    private static final long HOLD_TTL_MINUTES = 15;

    public boolean holdSeat(String eventId, String seatId, String userId) {
        String lockKey = "lock:seat:" + eventId + ":" + seatId;
        String holdKey = "seat:hold:" + eventId + ":" + seatId; // Fixed prefix to match TicketService
        String eventHeldSetKey = "event:" + eventId + ":held_seats";

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) { // use default watchdog lease time instead of strict 1000ms
                try {
                    RBucket<String> bucket = redissonClient.getBucket(holdKey);
                    if (bucket.isExists()) {
                        return bucket.get().equals(userId); 
                    }
                    
                    bucket.set(userId, HOLD_TTL_MINUTES, TimeUnit.MINUTES);
                    redissonClient.getSet(eventHeldSetKey).add(seatId); // Add to set for TicketService
                    
                    websocketService.broadcastSeatStatus(eventId, seatId, "HELD");
                    return true;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock interrupted", e);
        }
        return false;
    }

    public void releaseSeat(String eventId, String seatId, String userId) {
        String holdKey = "seat:hold:" + eventId + ":" + seatId;
        String eventHeldSetKey = "event:" + eventId + ":held_seats";
        
        RBucket<String> bucket = redissonClient.getBucket(holdKey);
        
        if (bucket.isExists() && bucket.get().equals(userId)) {
            bucket.delete();
            redissonClient.getSet(eventHeldSetKey).remove(seatId); // Remove from set
            websocketService.broadcastSeatStatus(eventId, seatId, "AVAILABLE");
        }
    }
}
