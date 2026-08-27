package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.EkycProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EkycProfileRepository extends JpaRepository<EkycProfile, String> {
}
