package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.HomepageBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Truy vấn banner hiển thị ở trang chủ {@code HomepageBanner}. */
@Repository
public interface HomepageBannerRepository extends JpaRepository<HomepageBanner, String> {
}
