package com.haloticket.payment_service.repository;
import com.haloticket.payment_service.entity.PayoutPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PayoutPolicyRepository extends JpaRepository<PayoutPolicy, String> {}
