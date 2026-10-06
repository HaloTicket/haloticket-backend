package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.HomepageBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HomepageBannerRepository extends JpaRepository<HomepageBanner, String> {
}
