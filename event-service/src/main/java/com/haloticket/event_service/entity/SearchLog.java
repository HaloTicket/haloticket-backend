package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Thực thể thống kê từ khóa tìm kiếm (bảng {@code search_logs}): mỗi từ khóa một dòng kèm số lần tìm. */
@Entity
@Table(name = "search_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "query_term", nullable = false, unique = true)
    private String queryTerm;

    @Column(name = "search_count")
    private Long searchCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** Trước khi lưu lần đầu: đặt thời điểm tạo; số lần tìm mặc định là 1. */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.searchCount == null) this.searchCount = 1L;
    }
}
