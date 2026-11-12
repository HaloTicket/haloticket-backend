package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;

import java.util.List;
import java.util.Optional;

public interface VoucherService {
    List<VoucherResponse> getVouchersByEventId(String eventId);
    List<VoucherResponse> createVoucher(String eventId, VoucherCreateRequest request);
    VoucherResponse updateVoucher(String voucherId, VoucherUpdateRequest request);
    VoucherResponse toggleVoucherStatus(String voucherId);
    VoucherResponse useVoucher(String voucherId, int count);
    // released (not draft) voucher of the event with this code, so the buyer who types it can see its terms
    Optional<VoucherResponse> findReleasedByCode(String eventId, String code);
    void deleteVoucher(String voucherId);

    // event a voucher belongs to (404 when the voucher does not exist).
    String eventIdOf(String voucherId);
}
