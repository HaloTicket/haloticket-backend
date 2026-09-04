package com.haloticket.user_service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Shared quota primitive for registration/reset callers; replace storage with Redis for multi-instance deployment. */
public class OtpRateLimiter {
    static final Duration WINDOW = Duration.ofHours(1);
    private final int maxPerSubject;
    private final int maxPerIp;
    private final Map<String, Window> subjects = new HashMap<>();
    private final Map<String, Window> ips = new HashMap<>();

    public OtpRateLimiter(int maxPerSubject, int maxPerIp) {
        if (maxPerSubject < 1 || maxPerIp < 1) throw new IllegalArgumentException("limits must be positive");
        this.maxPerSubject = maxPerSubject;
        this.maxPerIp = maxPerIp;
    }

    public synchronized boolean tryAcquire(String subject, String ipHash, Instant now) {
        if (subject == null || subject.isBlank() || ipHash == null || ipHash.isBlank() || now == null) return false;
        String normalizedSubject = subject.trim();
        String normalizedIp = ipHash.trim();
        if (!available(subjects, normalizedSubject, maxPerSubject, now)
                || !available(ips, normalizedIp, maxPerIp, now)) return false;
        increment(subjects, normalizedSubject, now);
        increment(ips, normalizedIp, now);
        return true;
    }

    private boolean available(Map<String, Window> buckets, String key, int limit, Instant now) {
        Window window = buckets.get(key);
        return window == null || !now.isBefore(window.startedAt.plus(WINDOW)) || window.count < limit;
    }

    private void increment(Map<String, Window> buckets, String key, Instant now) {
        buckets.compute(key, (ignored, current) ->
                current == null || !now.isBefore(current.startedAt.plus(WINDOW))
                        ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
    }

    private static final class Window {
        private final Instant startedAt;
        private int count;
        private Window(Instant startedAt, int count) { this.startedAt = startedAt; this.count = count; }
    }
}
