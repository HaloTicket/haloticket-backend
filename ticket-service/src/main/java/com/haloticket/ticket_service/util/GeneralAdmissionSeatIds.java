package com.haloticket.ticket_service.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Nhận dạng mã ghế ảo của vé vào cửa chung (GA) và tách mã loại vé ra khỏi mã đó. */
public final class GeneralAdmissionSeatIds {

    private static final String EXACT_GA = "GENERAL_ADMISSION";
    private static final Pattern GA_STT_PATTERN = Pattern.compile("^.+-(GA|STT)-\\d+$");
    private static final Pattern EXTRACT_TYPE_ID_PATTERN = Pattern.compile("^(.+)-(GA|STT)-\\d+$");
    /** Dạng mã chatbot khách hàng gửi cho vé đứng: {@code <zoneId>-AI-<timestamp>-<n>} (web AIChatMessage). */
    private static final Pattern CHATBOT_PATTERN = Pattern.compile("^(.+)-AI-\\d+-\\d+$");

    /** Chặn việc tạo đối tượng của lớp tiện ích. */
    private GeneralAdmissionSeatIds() {
        // lớp tiện ích, không tạo đối tượng
    }

    /**
     * Kiểm tra mã ghế có phải ghế ảo của vé vào cửa chung (GA), tức không thuộc sơ đồ ghế, hay không.
     * Khớp {@code GENERAL_ADMISSION} (không phân biệt hoa thường), regex {@code ^.+-(GA|STT)-\d+$}
     * hoặc dạng của chatbot.
     *
     * @param seatId mã ghế cần kiểm tra
     * @return true nếu là dạng GA, STT hoặc chatbot, ngược lại false
     */
    public static boolean isGeneralAdmission(String seatId) {
        if (seatId == null) {
            return false;
        }
        String trimmed = seatId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (EXACT_GA.equalsIgnoreCase(trimmed)) {
            return true;
        }
        return GA_STT_PATTERN.matcher(trimmed).matches() || CHATBOT_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Tách mã loại vé ở phần đầu của mã ghế GA ảo nếu có.
     * Ví dụ: {@code e7351c0a-edc3-4757-ab1c-45be5e2e853d-STT-1} cho ra {@code e7351c0a-edc3-4757-ab1c-45be5e2e853d}.
     *
     * @param seatId mã ghế
     * @return mã loại vé nếu đúng định dạng, ngược lại null
     */
    public static String extractTicketTypeId(String seatId) {
        if (seatId == null) {
            return null;
        }
        String trimmed = seatId.trim();
        Matcher matcher = EXTRACT_TYPE_ID_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        Matcher chatbot = CHATBOT_PATTERN.matcher(trimmed);
        if (chatbot.matches()) {
            return chatbot.group(1);
        }
        return null;
    }
}
