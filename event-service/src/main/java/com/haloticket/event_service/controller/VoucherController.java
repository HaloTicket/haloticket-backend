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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;
    private final EventAccessService access;

    /** Shared secret of the other services (env AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN); blank means no internal caller is accepted. */
    @Value("${authz.event-scope.internal-token:}")
    private String internalToken = "";

    @GetMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> getVouchersByEventId(@PathVariable("eventId") String eventId) {
        List<VoucherResponse> vouchers = voucherService.getVouchersByEventId(eventId);
        if (!mayManageVouchers(eventId)) {
            // the list stays public (checkout shows the offers) but the codes are for the people who run them,
            // and a voucher that is still a draft has not been released, so it is not shown at all
            vouchers = vouchers.stream()
                    .filter(voucher -> !"DRAFT".equalsIgnoreCase(String.valueOf(voucher.getStatus())))
                    .collect(Collectors.toList());
            vouchers.forEach(voucher -> voucher.setCode(null));
        }
        return ResponseEntity.ok(vouchers);
    }

    /** The buyer typed a code: return that voucher (and only that one) when it has been released. */
    @GetMapping("/{eventId}/vouchers/lookup")
    public ResponseEntity<VoucherResponse> lookupVoucher(
            @PathVariable("eventId") String eventId,
            @RequestParam("code") String code) {
        return voucherService.findReleasedByCode(eventId, code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean mayManageVouchers(String eventId) {
        try {
            access.requireForRequest(eventId, Permission.VOUCHER_MANAGE);
            return true;
        } catch (ResponseStatusException notAllowed) {
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

    @PostMapping("/vouchers/{voucherId}/use")
    public ResponseEntity<VoucherResponse> useVoucher(
            @PathVariable("voucherId") String voucherId,
            @RequestParam(name = "count", defaultValue = "1") int count,
            @RequestHeader(name = "X-Internal-Token", required = false) String token) {
        if (!isInternalCaller(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INTERNAL_CALLER_ONLY");
        }
        return ResponseEntity.ok(voucherService.useVoucher(voucherId, count));
    }

    private boolean isInternalCaller(String token) {
        if (internalToken == null || internalToken.isBlank() || token == null) return false;
        return java.security.MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
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
