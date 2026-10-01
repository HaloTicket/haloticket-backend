package com.haloticket.event_service.client;

import java.util.List;

public record ReportPaymentsResponse(List<ReportPayment> payments, long missingTimestampCount) {}
