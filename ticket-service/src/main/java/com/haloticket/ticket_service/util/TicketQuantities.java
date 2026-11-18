package com.haloticket.ticket_service.util;

/**
 * Bốn con số client hiển thị cho một loại vé, suy ra từ ba cột được lưu
 * ({@code initial_quantity}, {@code total_quantity}, {@code reserved_quantity}). {@code total_quantity} là số còn trong kho, dù đang giữ hay không.
 */
public final class TicketQuantities {

    /** Chặn việc tạo đối tượng của lớp tiện ích. */
    private TicketQuantities() {
    }

    /** Số còn trong kho, gồm cả phần các booking chưa thanh toán đang giữ. */
    public static Integer remaining(Integer total) {
        return total;
    }

    /** Số đang bị các booking vé vào cửa chung chưa thanh toán giữ; không âm, null tính là 0. */
    public static int held(Integer reserved) {
        return reserved == null ? 0 : Math.max(0, reserved);
    }

    /** Số còn mua được ngay: còn lại trừ đang giữ, không âm; null khi chưa biết số còn lại. */
    public static Integer available(Integer total, Integer reserved) {
        return total == null ? null : Math.max(0, total - held(reserved));
    }

    /**
     * Số đã bán: ban đầu trừ còn lại, không âm. Trả null (chưa biết) khi thiếu một trong hai số, tức dòng cũ tạo trước khi
     * có {@code initial_quantity}; client không được hiển thị 0 cho trường hợp này.
     */
    public static Integer sold(Integer initial, Integer total) {
        return initial == null || total == null ? null : Math.max(0, initial - total);
    }
}
