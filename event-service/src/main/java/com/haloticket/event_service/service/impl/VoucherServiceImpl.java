package com.haloticket.event_service.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.Voucher;
import com.haloticket.event_service.enums.ApplyScope;
import com.haloticket.event_service.enums.VoucherStatus;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.VoucherRepository;
import com.haloticket.event_service.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class VoucherServiceImpl implements VoucherService {

    private final VoucherRepository voucherRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    };

    @Override
    @Transactional(readOnly = true)
    public List<VoucherResponse> getVouchersByEventId(String eventId) {
        List<Voucher> vouchers = voucherRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        return vouchers.stream().map(this::mapToVoucherResponse).collect(Collectors.toList());
    }

    @Override
    public List<VoucherResponse> createVoucher(String eventId, VoucherCreateRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        List<Voucher> createdVouchers = new ArrayList<>();
        boolean isBulk = "BULK".equalsIgnoreCase(request.getCreationMode());

        if (isBulk) {
            int count = (request.getBulkCount() != null && request.getBulkCount() > 0) ? request.getBulkCount() : 5;
            String prefix = (request.getCodePrefix() != null && !request.getCodePrefix().isBlank())
                    ? request.getCodePrefix().toUpperCase().trim()
                    : "PROMO";

            for (int i = 0; i < count; i++) {
                String randomCode = generateRandomCode(prefix, 6);
                Voucher voucher = buildVoucherEntity(event, request, randomCode);
                createdVouchers.add(voucherRepository.save(voucher));
            }
        } else {
            String code = request.getCode() != null ? request.getCode().toUpperCase().trim() : generateRandomCode("HALO", 6);
            if (voucherRepository.existsByCode(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã voucher đã tồn tại trên hệ thống!");
            }
            Voucher voucher = buildVoucherEntity(event, request, code);
            createdVouchers.add(voucherRepository.save(voucher));
        }

        return createdVouchers.stream().map(this::mapToVoucherResponse).collect(Collectors.toList());
    }

    @Override
    public VoucherResponse updateVoucher(String voucherId, VoucherUpdateRequest request) {
        Voucher voucher = voucherRepository.findById(voucherId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND"));

        if (request.getCode() != null && !request.getCode().equalsIgnoreCase(voucher.getCode())) {
            String newCode = request.getCode().toUpperCase().trim();
            if (voucherRepository.existsByCode(newCode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã voucher mới đã tồn tại!");
            }
            voucher.setCode(newCode);
        }

        if (request.getTitle() != null) voucher.setTitle(request.getTitle());
        if (request.getTerms() != null) voucher.setTerms(request.getTerms());
        if (request.getDiscountType() != null) voucher.setDiscountType(request.getDiscountType());
        if (request.getDiscountValue() != null) voucher.setDiscountValue(request.getDiscountValue());
        if (request.getApplyTicketLimitType() != null) voucher.setApplyTicketLimitType(request.getApplyTicketLimitType());
        if (request.getTicketLimitCount() != null) voucher.setTicketLimitCount(request.getTicketLimitCount());
        if (request.getMaxOrdersPerBuyer() != null) voucher.setMaxOrdersPerBuyer(request.getMaxOrdersPerBuyer());
        if (request.getMinTicketsPerOrder() != null) voucher.setMinTicketsPerOrder(request.getMinTicketsPerOrder());
        if (request.getMaxTicketsPerOrder() != null) voucher.setMaxTicketsPerOrder(request.getMaxTicketsPerOrder());
        if (request.getApplyScope() != null) voucher.setApplyScope(request.getApplyScope());

        if (request.getApplicableShowtimes() != null) {
            try {
                voucher.setApplicableShowtimes(objectMapper.writeValueAsString(request.getApplicableShowtimes()));
            } catch (Exception e) {
                voucher.setApplicableShowtimes("[]");
            }
        }

        if (request.getStartDate() != null) voucher.setStartAt(parseDateTime(request.getStartDate()));
        if (request.getEndDate() != null) voucher.setEndAt(parseDateTime(request.getEndDate()));
        if (request.getStatus() != null) voucher.setStatus(request.getStatus());

        Voucher updated = voucherRepository.save(voucher);
        return mapToVoucherResponse(updated);
    }

    @Override
    public VoucherResponse toggleVoucherStatus(String voucherId) {
        Voucher voucher = voucherRepository.findById(voucherId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND"));

        if (voucher.getStatus() == VoucherStatus.ACTIVE) {
            voucher.setStatus(VoucherStatus.INACTIVE);
        } else {
            voucher.setStatus(VoucherStatus.ACTIVE);
        }

        Voucher updated = voucherRepository.save(voucher);
        return mapToVoucherResponse(updated);
    }

    @Override
    public void deleteVoucher(String voucherId) {
        if (!voucherRepository.existsById(voucherId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND");
        }
        voucherRepository.deleteById(voucherId);
    }

    private Voucher buildVoucherEntity(Event event, VoucherCreateRequest request, String code) {
        String showtimesJson = "[]";
        if (request.getApplicableShowtimes() != null && !request.getApplicableShowtimes().isEmpty()) {
            try {
                showtimesJson = objectMapper.writeValueAsString(request.getApplicableShowtimes());
            } catch (Exception e) {
                showtimesJson = "[]";
            }
        }

        return Voucher.builder()
                .event(event)
                .code(code)
                .title(request.getTitle() != null ? request.getTitle() : "Chương trình khuyến mãi")
                .terms(request.getTerms())
                .discountType(request.getDiscountType())
                .discountValue(request.getDiscountValue())
                .applyTicketLimitType(request.getApplyTicketLimitType() != null ? request.getApplyTicketLimitType() : "LIMITED")
                .ticketLimitCount(request.getTicketLimitCount() != null ? request.getTicketLimitCount() : 100)
                .maxOrdersPerBuyer(request.getMaxOrdersPerBuyer() != null ? request.getMaxOrdersPerBuyer() : 1)
                .minTicketsPerOrder(request.getMinTicketsPerOrder() != null ? request.getMinTicketsPerOrder() : 1)
                .maxTicketsPerOrder(request.getMaxTicketsPerOrder() != null ? request.getMaxTicketsPerOrder() : 5)
                .applyScope(request.getApplyScope() != null ? request.getApplyScope() : ApplyScope.ALL)
                .applicableShowtimes(showtimesJson)
                .startAt(parseDateTime(request.getStartDate()))
                .endAt(parseDateTime(request.getEndDate()))
                .status(VoucherStatus.ACTIVE)
                .usedCount(0)
                .build();
    }

    private VoucherResponse mapToVoucherResponse(Voucher voucher) {
        List<String> showtimes = new ArrayList<>();
        if (voucher.getApplicableShowtimes() != null && !voucher.getApplicableShowtimes().isBlank()) {
            try {
                showtimes = objectMapper.readValue(voucher.getApplicableShowtimes(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                // ignore
            }
        }

        DateTimeFormatter outputFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        return VoucherResponse.builder()
                .id(voucher.getId())
                .eventId(voucher.getEvent() != null ? voucher.getEvent().getId() : null)
                .title(voucher.getTitle())
                .code(voucher.getCode())
                .terms(voucher.getTerms())
                .discountType(voucher.getDiscountType())
                .discountValue(voucher.getDiscountValue())
                .applyTicketLimitType(voucher.getApplyTicketLimitType())
                .ticketLimitCount(voucher.getTicketLimitCount())
                .maxOrdersPerBuyer(voucher.getMaxOrdersPerBuyer())
                .minTicketsPerOrder(voucher.getMinTicketsPerOrder())
                .maxTicketsPerOrder(voucher.getMaxTicketsPerOrder())
                .applyScope(voucher.getApplyScope())
                .applicableShowtimes(showtimes)
                .usedCount(voucher.getUsedCount())
                .startDate(voucher.getStartAt() != null ? voucher.getStartAt().format(outputFmt) : "")
                .endDate(voucher.getEndAt() != null ? voucher.getEndAt().format(outputFmt) : "")
                .status(voucher.getStatus())
                .isActive(voucher.getStatus() == VoucherStatus.ACTIVE)
                .createdAt(voucher.getCreatedAt() != null ? voucher.getCreatedAt().format(outputFmt) : "")
                .updatedAt(voucher.getUpdatedAt() != null ? voucher.getUpdatedAt().format(outputFmt) : "")
                .build();
    }

    private LocalDateTime parseDateTime(String input) {
        if (input == null || input.isBlank()) return LocalDateTime.now();
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(input, fmt);
            } catch (Exception e) {
                // try next
            }
        }
        return LocalDateTime.now();
    }

    private String generateRandomCode(String prefix, int randomLength) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(prefix.toUpperCase());
        Random random = new Random();
        for (int i = 0; i < randomLength; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        String candidate = sb.toString();
        if (voucherRepository.existsByCode(candidate)) {
            return generateRandomCode(prefix, randomLength);
        }
        return candidate;
    }
}
