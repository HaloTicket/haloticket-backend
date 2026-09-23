package com.haloticket.booking_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.client.codec.StringCodec;

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

    public boolean holdSeat(String eventId, String showtimeId, String seatId, String userId) {
        String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
        String lockKey = "lock:seat:" + showtimePrefix + ":" + seatId;
        String holdKey = "seat:hold:" + showtimePrefix + ":" + seatId; // fixed prefix to match TicketService
        String eventHeldSetKey = showtimePrefix + ":held_seats";

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) { // use default watchdog lease time instead of strict 1000ms
                try {
                    var bucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
                    if (bucket.isExists()) {
                        return userId.equals(bucket.get()); 
                    }
                    
                    bucket.set(userId, HOLD_TTL_MINUTES, TimeUnit.MINUTES);
                    redissonClient.getSet(eventHeldSetKey).add(seatId); // add to set for TicketService
                    
                    websocketService.broadcastSeatStatus(eventId, showtimeId, seatId, "HELD");
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

    public void releaseSeat(String eventId, String showtimeId, String seatId, String userId) {
        String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
        String holdKey = "seat:hold:" + showtimePrefix + ":" + seatId;
        String eventHeldSetKey = showtimePrefix + ":held_seats";
        
        var bucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
        
        if (bucket.isExists() && userId.equals(bucket.get())) {
            bucket.delete();
            redissonClient.getSet(eventHeldSetKey).remove(seatId); // remove from set
            websocketService.broadcastSeatStatus(eventId, showtimeId, seatId, "AVAILABLE");
        }
    }
}
