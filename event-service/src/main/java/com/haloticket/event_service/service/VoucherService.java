package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;

import java.util.List;

public interface VoucherService {
    List<VoucherResponse> getVouchersByEventId(String eventId);
    List<VoucherResponse> createVoucher(String eventId, VoucherCreateRequest request);
    VoucherResponse updateVoucher(String voucherId, VoucherUpdateRequest request);
    VoucherResponse toggleVoucherStatus(String voucherId);
    void deleteVoucher(String voucherId);

    // event a voucher belongs to (404 when the voucher does not exist).
    String eventIdOf(String voucherId);
}
