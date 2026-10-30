package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.service.EventAccessService;
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
    private final EventAccessService access;

    @GetMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> getVouchersByEventId(@PathVariable("eventId") String eventId) {
        List<VoucherResponse> vouchers = voucherService.getVouchersByEventId(eventId);
        if (!mayManageVouchers(eventId)) {
            // the list stays public (checkout shows the offers) but the codes are for the people who run them
            vouchers.forEach(voucher -> voucher.setCode(null));
        }
        return ResponseEntity.ok(vouchers);
    }

    private boolean mayManageVouchers(String eventId) {
        try {
            access.requireForRequest(eventId, Permission.VOUCHER_MANAGE);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException notAllowed) {
            return false;
        }
    }

    @PostMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> createVoucher(
            @PathVariable("eventId") String eventId,
            @RequestBody VoucherCreateRequest request) {
        access.requireForRequest(eventId, Permission.VOUCHER_MANAGE);
        return new ResponseEntity<>(voucherService.createVoucher(eventId, request), HttpStatus.CREATED);
    }

    @PutMapping("/vouchers/{voucherId}")
    public ResponseEntity<VoucherResponse> updateVoucher(
            @PathVariable("voucherId") String voucherId,
            @RequestBody VoucherUpdateRequest request) {
        requireVoucherManager(voucherId);
        return ResponseEntity.ok(voucherService.updateVoucher(voucherId, request));
    }

    @PatchMapping("/vouchers/{voucherId}/status")
    public ResponseEntity<VoucherResponse> toggleVoucherStatus(@PathVariable("voucherId") String voucherId) {
        requireVoucherManager(voucherId);
        return ResponseEntity.ok(voucherService.toggleVoucherStatus(voucherId));
    }

    @DeleteMapping("/vouchers/{voucherId}")
    public ResponseEntity<Void> deleteVoucher(@PathVariable("voucherId") String voucherId) {
        requireVoucherManager(voucherId);
        voucherService.deleteVoucher(voucherId);
        return ResponseEntity.noContent().build();
    }

    /** The voucher's own event decides (a voucher id of another event is not enough): 404 for an unknown voucher, 403 otherwise. */
    private void requireVoucherManager(String voucherId) {
        access.requireForRequest(voucherService.eventIdOf(voucherId), Permission.VOUCHER_MANAGE);
    }
}
