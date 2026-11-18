package com.haloticket.ticket_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.client.EventServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lấy lịch và thông tin sự kiện từ event-service cho nghiệp vụ check-in, có hai tầng cache (Redis rồi bộ nhớ trong)
 * với thời hạn 5 phút để giảm số lần gọi Feign.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventScheduleService {

    private final EventServiceClient eventServiceClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String REDIS_PREFIX = "checkin:event_schedule:";

    private final Map<String, LocalCacheEntry> memoryCache = new ConcurrentHashMap<>();

    /** Mục cache trong bộ nhớ: dữ liệu sự kiện và thời điểm hết hạn. */
    private record LocalCacheEntry(EventServiceClient.EventDetailsResponse data, Instant expiresAt) {}

    /**
     * Lấy thông tin sự kiện theo thứ tự: Redis, cache bộ nhớ, rồi gọi event-service. Lỗi Redis chỉ ghi log mức debug và bị bỏ qua.
     *
     * @param eventId mã sự kiện
     * @return thông tin sự kiện, hoặc null khi mã trống hoặc event-service không trả dữ liệu
     */
    public EventServiceClient.EventDetailsResponse getEventSchedule(String eventId) {
        if (eventId == null || eventId.isBlank()) return null;

        // 1. thử cache Redis
        String redisKey = REDIS_PREFIX + eventId;
        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(redisKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                return objectMapper.readValue(cachedJson, EventServiceClient.EventDetailsResponse.class);
            }
        } catch (Exception ex) {
            log.debug("Redis cache get error for {}: {}", redisKey, ex.getMessage());
        }

        // 2. thử cache dự phòng trong bộ nhớ
        LocalCacheEntry entry = memoryCache.get(eventId);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.data();
        }

        // 3. gọi event-service qua Feign
        EventServiceClient.EventDetailsResponse response = eventServiceClient.getEventById(eventId);
        if (response != null) {
            // ghi vào Redis
            try {
                String json = objectMapper.writeValueAsString(response);
                stringRedisTemplate.opsForValue().set(redisKey, json, CACHE_TTL);
            } catch (Exception ex) {
                log.debug("Redis cache put error for {}: {}", redisKey, ex.getMessage());
            }
            // ghi vào cache bộ nhớ
            memoryCache.put(eventId, new LocalCacheEntry(response, Instant.now().plus(CACHE_TTL)));
        }

        return response;
    }
}
