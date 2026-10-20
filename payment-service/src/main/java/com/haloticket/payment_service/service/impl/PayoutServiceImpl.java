package com.haloticket.payment_service.service.impl;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.enums.PayoutStatus;
import com.haloticket.payment_service.repository.PayoutRepository;
import com.haloticket.payment_service.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import java.text.NumberFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PayoutServiceImpl implements PayoutService {

    private final PayoutRepository payoutRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public PayoutResponse requestPayout(String organizerId, PayoutCreateRequest request) {
        // mock business logic: platform fee is 5% unless specified by request
        Double gross = request.getRequestAmount();
        Double rate = request.getPlatformFeeRate() != null ? request.getPlatformFeeRate() : 0.05;
        Double platformFee = gross * rate;
        Double net = gross - platformFee;

        Payout payout = Payout.builder()
                .eventId(request.getEventId())
                .organizerUserId(organizerId)
                .grossAmount(gross)
                .platformFee(platformFee)
                .netAmount(net)
                .status(PayoutStatus.PENDING)
                .build();

        Payout saved = payoutRepository.save(payout);

        // send realtime notification to Admin
        String messagePayload = String.format("{\"recipientUserId\":\"admin\",\"type\":\"PAYOUT_REQUESTED\",\"title\":\"Yêu cầu đối soát mới\",\"message\":\"Có yêu cầu đối soát mới từ Ban tổ chức cho sự kiện %s\"}", request.getEventId());
        kafkaTemplate.send("haloticket.payout.requested.v1", "admin", messagePayload);

        return mapToResponse(saved);
    }

    @Override
    public PayoutResponse approvePayout(String payoutId) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        payout.setStatus(PayoutStatus.COMPLETED);
        payout.setTransferredAt(LocalDateTime.now());
        Payout saved = payoutRepository.save(payout);

        // send realtime notification to Organizer safely
        try {
            String formattedAmount = "0";
            if (payout.getNetAmount() != null) {
                NumberFormat nf = NumberFormat.getInstance(Locale.GERMANY);
                formattedAmount = nf.format(payout.getNetAmount());
            }
            String messagePayload = String.format("{\"recipientUserId\":\"%s\",\"type\":\"PAYOUT_APPROVED\",\"title\":\"Đối soát thành công\",\"message\":\"Yêu cầu đối soát cho sự kiện %s đã được duyệt. Số tiền %sđ đã được chuyển khoản.\"}", 
                payout.getOrganizerUserId(), payout.getEventId(), formattedAmount);
            kafkaTemplate.send("haloticket.payout.approved.v1", payout.getOrganizerUserId(), messagePayload);
        } catch (Exception ignored) {}

        return mapToResponse(saved);
    }

    @Override
    public PayoutResponse disputePayout(String payoutId, String reason) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        payout.setStatus(PayoutStatus.DISPUTE);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    public PayoutResponse reopenPayout(String payoutId) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        payout.setStatus(PayoutStatus.PENDING);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    public PayoutResponse rejectPayout(String payoutId) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        payout.setStatus(PayoutStatus.FAILED);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    public List<PayoutResponse> getPendingPayouts() {
        return payoutRepository.findByStatus(PayoutStatus.PENDING)
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public List<PayoutResponse> getAllAdminPayouts() {
        return payoutRepository.findAll().stream()
                .map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public List<PayoutResponse> getOrganizerPayouts(String organizerId) {
        return payoutRepository.findByOrganizerUserId(organizerId)
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private PayoutResponse mapToResponse(Payout p) {
        return PayoutResponse.builder()
                .id(p.getId())
                .eventId(p.getEventId())
                .organizerUserId(p.getOrganizerUserId())
                .grossAmount(p.getGrossAmount())
                .platformFee(p.getPlatformFee())
                .netAmount(p.getNetAmount())
                .status(p.getStatus())
                .transferredAt(p.getTransferredAt())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
