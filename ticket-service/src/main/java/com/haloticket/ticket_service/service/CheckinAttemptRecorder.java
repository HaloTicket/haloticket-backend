package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Lưu lần quét bị từ chối trong một giao dịch riêng. Quét bị từ chối kết thúc bằng {@code CheckinException} làm hoàn tác
 * giao dịch check-in, kéo theo cả dòng ghi nhận. Ở đây dòng ghi nhận được commit độc lập, và vì ghi được flush ngay trong
 * giao dịch này nên mọi lỗi cơ sở dữ liệu (ví dụ ràng buộc CHECK cũ) lộ ra tại đây, chỉ hoàn tác giao dịch nhỏ này
 * và tới nơi gọi để nơi đó quyết định cách xử lý nhẹ nhàng.
 */
@Component
@RequiredArgsConstructor
public class CheckinAttemptRecorder {

    private final CheckInAttemptRepository attempts;
    private final TicketRepository tickets;

    /**
     * Ghi một lần quét bị từ chối. Nhân viên hoặc thiết bị không có thông tin được ghi là {@code UNKNOWN_STAFF} và {@code UNKNOWN_DEVICE}.
     *
     * @param ticketId mã vé (null nếu mã quét không khớp vé nào)
     * @param request yêu cầu check-in gốc, có thể null
     * @param result kết quả của lần quét
     * @param failureReason lý do từ chối
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String ticketId, CheckinRequest request, CheckInAttemptResult result, String failureReason) {
        String staffId = request != null ? request.getStaffAssignmentId() : null;
        String device = request != null ? request.getDeviceInfo() : null;
        attempts.saveAndFlush(CheckInAttempt.builder()
                // tham chiếu theo id, không tốn thêm truy vấn; null khi mã quét không khớp vé nào (cột cho phép null).
                .ticket(ticketId == null ? null : tickets.getReferenceById(ticketId))
                .staffAssignmentId(staffId == null || staffId.trim().isEmpty() ? "UNKNOWN_STAFF" : staffId)
                .deviceInfo(device == null || device.trim().isEmpty() ? "UNKNOWN_DEVICE" : device)
                .result(result)
                .failureReason(failureReason)
                .attemptedAt(LocalDateTime.now())
                .build());
    }
}
