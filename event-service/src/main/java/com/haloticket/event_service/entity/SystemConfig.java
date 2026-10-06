package com.haloticket.event_service.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "system_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SystemConfig {
    @Id
    private String id;

    private String platformFee;
    private String payoutHoldDays;
    private String kycRequired;
    private String maintenanceMode;
}
