package com.haloticket.booking_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.client.codec.StringCodec;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** Giữ và nhả ghế tạm thời bằng khóa Redisson và khóa Redis có thời hạn 15 phút, rồi báo trạng thái ghế qua WebSocket. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    private final RedissonClient redissonClient;
    private final SeatWebsocketService websocketService;

    private static final long HOLD_TTL_MINUTES = 15;

    /**
     * Giữ một ghế cho người dùng trong 15 phút. Dùng khóa phân tán để hai người không giữ cùng ghế; nếu ghế đã bị giữ thì chỉ thành công khi
     * đúng người đang giữ.
     * @param eventId mã sự kiện
     * @param showtimeId mã suất chiếu, null thì khóa theo sự kiện
     * @param seatId mã ghế
     * @param userId mã người giữ (mã người dùng hoặc id phiên ẩn danh)
     * @return true khi giữ được (hoặc đã giữ sẵn bởi chính người này), false khi ghế bị người khác giữ hoặc không lấy được khóa
     */
    public boolean holdSeat(String eventId, String showtimeId, String seatId, String userId) {
        String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
        String lockKey = "lock:seat:" + showtimePrefix + ":" + seatId;
        String holdKey = "seat:hold:" + showtimePrefix + ":" + seatId; // tiền tố cố định để khớp với TicketService
        String eventHeldSetKey = showtimePrefix + ":held_seats";

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) { // dùng thời gian thuê mặc định của watchdog thay vì 1000ms cứng nhắc
                try {
                    var bucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
                    if (bucket.isExists()) {
                        return userId.equals(bucket.get()); 
                    }
                    
                    bucket.set(userId, HOLD_TTL_MINUTES, TimeUnit.MINUTES);
                    redissonClient.getSet(eventHeldSetKey).add(seatId); // thêm vào tập để TicketService đọc
                    
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

    /**
     * Nhả ghế đang giữ, chỉ khi đúng người đang giữ, rồi báo ghế trống qua WebSocket.
     * @param eventId mã sự kiện
     * @param showtimeId mã suất chiếu, null thì khóa theo sự kiện
     * @param seatId mã ghế
     * @param userId mã người nhả, phải là người đang giữ
     */
    public void releaseSeat(String eventId, String showtimeId, String seatId, String userId) {
        String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
        String holdKey = "seat:hold:" + showtimePrefix + ":" + seatId;
        String eventHeldSetKey = showtimePrefix + ":held_seats";
        
        var bucket = redissonClient.getBucket(holdKey, StringCodec.INSTANCE);
        
        if (bucket.isExists() && userId.equals(bucket.get())) {
            bucket.delete();
            redissonClient.getSet(eventHeldSetKey).remove(seatId); // xóa khỏi tập
            websocketService.broadcastSeatStatus(eventId, showtimeId, seatId, "AVAILABLE");
        }
    }
}
