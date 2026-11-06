package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** booking.reconcile.*: admin reconciliation of orders. */
@Data
@Component
@ConfigurationProperties(prefix = "booking.reconcile")
public class ReconciliationProperties {
    /** A PAID order younger than this is not flagged yet: issuing tickets takes a moment. */
    private int paidGraceMinutes = 10;
    /** Bookings checked per ticket-service call (ticket-service accepts up to 500). */
    private int batchSize = 200;
    /** Batches checked per request, so one request cannot become a table scan. */
    private int maxBatches = 5;
    /** Only orders paid within this many days are checked. */
    private int lookbackDays = 14;
    /** Rows read from ticket-service stock reconciliation. */
    private int stockLimit = 200;
}
