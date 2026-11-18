package com.haloticket.user_service.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bộ giới hạn số lần gửi OTP theo đối tượng nhận và theo IP trong mỗi cửa sổ 1 giờ. Dữ liệu nằm trong bộ nhớ, cần thay bằng Redis khi chạy nhiều instance. */
public class OtpRateLimiter {
    static final Duration WINDOW = Duration.ofHours(1);
    private final int maxPerSubject;
    private final int maxPerIp;
    private final Map<String, Window> subjects = new HashMap<>();
    private final Map<String, Window> ips = new HashMap<>();

    /**
     * Tạo bộ giới hạn.
     *
     * @param maxPerSubject số lần tối đa cho mỗi đối tượng nhận trong một cửa sổ
     * @param maxPerIp số lần tối đa cho mỗi IP trong một cửa sổ
     * @throws IllegalArgumentException khi một giới hạn nhỏ hơn 1
     */
    public OtpRateLimiter(int maxPerSubject, int maxPerIp) {
        if (maxPerSubject < 1 || maxPerIp < 1) throw new IllegalArgumentException("limits must be positive");
        this.maxPerSubject = maxPerSubject;
        this.maxPerIp = maxPerIp;
    }

    /**
     * Xin một lượt gửi: chỉ tăng bộ đếm khi cả đối tượng nhận và IP còn hạn mức.
     *
     * @param subject đối tượng nhận OTP
     * @param ipHash mã băm IP của người gọi
     * @param now thời điểm hiện tại
     * @return {@code true} nếu được phép gửi, {@code false} nếu hết hạn mức hoặc tham số thiếu
     */
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

    /** Kiểm tra khóa còn hạn mức: chưa có cửa sổ, cửa sổ đã hết hạn hoặc số lần dùng chưa chạm giới hạn. */
    private boolean available(Map<String, Window> buckets, String key, int limit, Instant now) {
        Window window = buckets.get(key);
        return window == null || !now.isBefore(window.startedAt.plus(WINDOW)) || window.count < limit;
    }

    /** Tăng bộ đếm của khóa, mở cửa sổ mới nếu chưa có hoặc cửa sổ cũ đã hết hạn. */
    private void increment(Map<String, Window> buckets, String key, Instant now) {
        buckets.compute(key, (ignored, current) ->
                current == null || !now.isBefore(current.startedAt.plus(WINDOW))
                        ? new Window(now, 1) : new Window(current.startedAt, current.count + 1));
    }

    /** Cửa sổ đếm: thời điểm bắt đầu và số lượt đã dùng. */
    private static final class Window {
        private final Instant startedAt;
        private int count;
        /** Tạo cửa sổ với thời điểm bắt đầu và số lượt ban đầu. */
        private Window(Instant startedAt, int count) { this.startedAt = startedAt; this.count = count; }
    }
}
