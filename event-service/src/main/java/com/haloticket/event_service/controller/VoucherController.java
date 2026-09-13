package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    @GetMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> getVouchersByEventId(@PathVariable("eventId") String eventId) {
        return ResponseEntity.ok(voucherService.getVouchersByEventId(eventId));
    }

    @PostMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> createVoucher(
            @PathVariable("eventId") String eventId,
            @RequestBody VoucherCreateRequest request) {
        return new ResponseEntity<>(voucherService.createVoucher(eventId, request), HttpStatus.CREATED);
    }

    @PutMapping("/vouchers/{voucherId}")
    public ResponseEntity<VoucherResponse> updateVoucher(
            @PathVariable("voucherId") String voucherId,
            @RequestBody VoucherUpdateRequest request) {
        return ResponseEntity.ok(voucherService.updateVoucher(voucherId, request));
    }

    @PatchMapping("/vouchers/{voucherId}/status")
    public ResponseEntity<VoucherResponse> toggleVoucherStatus(@PathVariable("voucherId") String voucherId) {
        return ResponseEntity.ok(voucherService.toggleVoucherStatus(voucherId));
    }

    @DeleteMapping("/vouchers/{voucherId}")
    public ResponseEntity<Void> deleteVoucher(@PathVariable("voucherId") String voucherId) {
        voucherService.deleteVoucher(voucherId);
        return ResponseEntity.noContent().build();
    }
}
