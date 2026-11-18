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
import java.util.Optional;

/** Cài đặt nghiệp vụ voucher giảm giá của sự kiện: tạo (đơn lẻ hoặc hàng loạt), sửa, bật tắt, xóa, ghi nhận lượt dùng và tra cứu theo mã. */
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

    /**
     * Lấy id sự kiện của voucher.
     * @param voucherId id voucher
     * @return id sự kiện, null nếu voucher không gắn với sự kiện
     * @throws ResponseStatusException 404 {@code VOUCHER_NOT_FOUND}
     */
    @Override
    @Transactional(readOnly = true)
    public String eventIdOf(String voucherId) {
        Voucher voucher = voucherRepository.findById(voucherId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND"));
        return voucher.getEvent() == null ? null : voucher.getEvent().getId();
    }

    /** Một đơn không bao giờ chứa nhiều vé hơn mức này, nên số lượng lớn hơn là gọi sai chứ không phải đơn lớn. */
    static final int MAX_TICKETS_PER_USE = 100;

    /** Tìm voucher đã phát hành (khác {@code DRAFT}) của sự kiện theo mã, không phân biệt hoa thường; mã rỗng thì trả rỗng. */
    @Override
    @Transactional(readOnly = true)
    public Optional<VoucherResponse> findReleasedByCode(String eventId, String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        String wanted = code.trim();
        return voucherRepository.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .filter(v -> v.getStatus() != VoucherStatus.DRAFT && wanted.equalsIgnoreCase(v.getCode()))
                .findFirst().map(this::mapToVoucherResponse);
    }

    /** Lấy các voucher của sự kiện, mới tạo nhất trước. */
    @Override
    @Transactional(readOnly = true)
    public List<VoucherResponse> getVouchersByEventId(String eventId) {
        List<Voucher> vouchers = voucherRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        return vouchers.stream().map(this::mapToVoucherResponse).collect(Collectors.toList());
    }

    /**
     * Tạo voucher cho sự kiện. Chế độ {@code BULK} sinh {@code bulkCount} voucher (mặc định 5) với mã ngẫu nhiên có tiền tố (mặc định {@code PROMO}); chế độ đơn lẻ dùng mã được gửi lên (viết hoa) hoặc sinh ngẫu nhiên với tiền tố {@code HALO}. Các trường không gửi dùng giá trị mặc định.
     * @param eventId id sự kiện
     * @param request thông tin voucher
     * @return các voucher vừa tạo
     * @throws ResponseStatusException 404 {@code EVENT_NOT_FOUND}, 400 khi mã đơn lẻ đã tồn tại
     */
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

    /**
     * Cập nhật các trường được gửi lên (trường null giữ nguyên). Đổi mã phải không trùng mã voucher khác.
     * @param voucherId id voucher
     * @param request các trường cần đổi
     * @return voucher sau khi cập nhật
     * @throws ResponseStatusException 404 {@code VOUCHER_NOT_FOUND}, 400 khi mã mới đã tồn tại
     */
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

    /**
     * Đảo trạng thái voucher: đang {@code ACTIVE} thì thành {@code INACTIVE}, ngược lại thành {@code ACTIVE}.
     * @throws ResponseStatusException 404 {@code VOUCHER_NOT_FOUND}
     */
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

    /**
     * Ghi nhận voucher được dùng cho {@code count} vé. Khóa dòng voucher khi đọc để các thanh toán đồng thời không đếm trùng; với voucher giới hạn, khi số đã dùng chạm giới hạn thì tự chuyển {@code INACTIVE}.
     * @param voucherId id voucher
     * @param count số vé áp dụng, từ 1 đến {@code MAX_TICKETS_PER_USE}
     * @return voucher sau khi cập nhật số lượt dùng
     * @throws ResponseStatusException 400 {@code INVALID_VOUCHER_USE_COUNT}, 404 {@code VOUCHER_NOT_FOUND}
     */
    @Override
    @Transactional
    public VoucherResponse useVoucher(String voucherId, int count) {
        if (count < 1 || count > MAX_TICKETS_PER_USE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_VOUCHER_USE_COUNT");
        }
        Voucher voucher = voucherRepository.findByIdForUpdate(voucherId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND"));

        int currentUsed = voucher.getUsedCount() != null ? voucher.getUsedCount() : 0;
        int increment = count;
        voucher.setUsedCount(currentUsed + increment);

        // kiểm tra đã chạm giới hạn số vé hay chưa
        if ("LIMITED".equalsIgnoreCase(voucher.getApplyTicketLimitType()) && voucher.getTicketLimitCount() != null) {
            if (voucher.getUsedCount() >= voucher.getTicketLimitCount()) {
                voucher.setStatus(VoucherStatus.INACTIVE);
            }
        }

        Voucher updated = voucherRepository.save(voucher);
        return mapToVoucherResponse(updated);
    }

    /**
     * Xóa voucher.
     * @throws ResponseStatusException 404 {@code VOUCHER_NOT_FOUND}
     */
    @Override
    public void deleteVoucher(String voucherId) {
        if (!voucherRepository.existsById(voucherId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND");
        }
        voucherRepository.deleteById(voucherId);
    }

    /** Dựng entity voucher từ request với mã đã chọn, điền giá trị mặc định cho trường thiếu (giới hạn 100 vé, tối đa 5 vé mỗi đơn, phạm vi {@code ALL}, trạng thái {@code ACTIVE}). */
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
                .status(request.getStatus() != null ? request.getStatus() : VoucherStatus.ACTIVE)
                .usedCount(0)
                .build();
    }

    /** Chuyển voucher sang response: giải mã danh sách suất áp dụng từ JSON và định dạng ngày theo {@code dd/MM/yyyy HH:mm}. */
    private VoucherResponse mapToVoucherResponse(Voucher voucher) {
        List<String> showtimes = new ArrayList<>();
        if (voucher.getApplicableShowtimes() != null && !voucher.getApplicableShowtimes().isBlank()) {
            try {
                showtimes = objectMapper.readValue(voucher.getApplicableShowtimes(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                // bỏ qua, coi như không có suất áp dụng
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

    /** Đọc ngày giờ từ chuỗi theo các định dạng được hỗ trợ; chuỗi rỗng hoặc không đọc được thì trả thời điểm hiện tại. */
    private LocalDateTime parseDateTime(String input) {
        if (input == null || input.isBlank()) return LocalDateTime.now();
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDateTime.parse(input, fmt);
            } catch (Exception e) {
                // thử định dạng kế tiếp
            }
        }
        return LocalDateTime.now();
    }

    /** Sinh mã gồm tiền tố và {@code randomLength} ký tự chữ hoa hoặc số ngẫu nhiên; trùng mã đã có thì sinh lại. */
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
