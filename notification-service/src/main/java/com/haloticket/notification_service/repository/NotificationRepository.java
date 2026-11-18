package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Repository JPA truy vấn thông báo ({@code Notification}). */
public interface NotificationRepository extends JpaRepository<Notification, String> {
    /**
     * Tìm thông báo theo mã sự kiện (dùng để chống xử lý trùng).
     *
     * @param eventId mã sự kiện
     * @return thông báo nếu đã tồn tại
     */
    Optional<Notification> findByEventId(String eventId);
    /**
     * Lấy thông báo của người dùng, mới nhất trước.
     *
     * @param userId mã người dùng
     * @return danh sách thông báo
     */
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Lấy thông báo theo trạng thái, cũ nhất trước.
     *
     * @param status trạng thái gửi
     * @return danh sách thông báo
     */
    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status);
}
