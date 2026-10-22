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

    private record LocalCacheEntry(EventServiceClient.EventDetailsResponse data, Instant expiresAt) {}

    public EventServiceClient.EventDetailsResponse getEventSchedule(String eventId) {
        if (eventId == null || eventId.isBlank()) return null;

        // 1. try redis cache
        String redisKey = REDIS_PREFIX + eventId;
        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(redisKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                return objectMapper.readValue(cachedJson, EventServiceClient.EventDetailsResponse.class);
            }
        } catch (Exception ex) {
            log.debug("Redis cache get error for {}: {}", redisKey, ex.getMessage());
        }

        // 2. try in-memory fallback cache
        LocalCacheEntry entry = memoryCache.get(eventId);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.data();
        }

        // 3. fetch from event-service via Feign
        EventServiceClient.EventDetailsResponse response = eventServiceClient.getEventById(eventId);
        if (response != null) {
            // write to Redis
            try {
                String json = objectMapper.writeValueAsString(response);
                stringRedisTemplate.opsForValue().set(redisKey, json, CACHE_TTL);
            } catch (Exception ex) {
                log.debug("Redis cache put error for {}: {}", redisKey, ex.getMessage());
            }
            // write to memory cache
            memoryCache.put(eventId, new LocalCacheEntry(response, Instant.now().plus(CACHE_TTL)));
        }

        return response;
    }
}
