package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Truy vấn bảng danh mục sự kiện {@code EventCategory}. */
@Repository
public interface EventCategoryRepository extends JpaRepository<EventCategory, String> {
    /** Tìm danh mục có tên chứa chuỗi cho trước, không phân biệt hoa thường. */
    Optional<EventCategory> findByNameContainingIgnoreCase(String name);

    /** Tìm danh mục theo đúng tên, không phân biệt hoa thường. */
    Optional<EventCategory> findByNameIgnoreCase(String name);

    /** Tìm danh mục theo slug, không phân biệt hoa thường. */
    Optional<EventCategory> findBySlugIgnoreCase(String slug);

    /** Thứ tự các ứng dụng hiển thị danh mục: theo vị trí, danh mục chưa có vị trí xếp cuối, sau đó theo tên. */
    List<EventCategory> findAllByOrderBySortOrderAscNameAsc();
}
