package com.haloticket.user_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.user_service.enums.EkycDocumentType;
import com.haloticket.user_service.service.VnptEkycClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chuyển các phản hồi của VNPT (OCR, liveness, so khớp khuôn mặt) thành {@code VerificationResult}.
 * Hợp đồng API VNPT: /admin-dashboard/vi/documents/api, đọc ngày 2026-09-11.
 * Chỉ chấp nhận kết quả CCCD thành công rõ ràng và nhất quán.
 */
public class VnptEkycResponseMapper {
    private static final Logger log = LoggerFactory.getLogger(VnptEkycResponseMapper.class);
    private final ObjectMapper mapper;
    /** @param mapper dùng để tạo nút JSON rỗng khi phản hồi thiếu phần {@code object} */
    public VnptEkycResponseMapper(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * Ánh xạ các phản hồi của VNPT thành kết quả xác minh. Giấy tờ hợp lệ khi VNPT báo thành công, hai mặt đều OK và cùng loại,
     * số CCCD đủ 12 chữ số và có họ tên. Điểm khuôn mặt là {@code prob} chia 100, hoặc NaN nếu không đọc được.
     *
     * @param reference mã tham chiếu của phiên xác minh
     * @param ocrResponse phản hồi OCR
     * @param front phản hồi kiểm tra thật giả mặt trước (có thể null)
     * @param back phản hồi kiểm tra thật giả mặt sau (có thể null)
     * @param face phản hồi kiểm tra người thật của ảnh chân dung
     * @param compareResponse phản hồi so khớp khuôn mặt
     * @return kết quả xác minh
     */
    public VnptEkycClient.VerificationResult map(String reference, JsonNode ocrResponse,
            JsonNode front, JsonNode back, JsonNode face, JsonNode compareResponse) {
        JsonNode ocr = object(ocrResponse);
        String number = text(ocr, "id");
        String name = text(ocr, "name");
        String birthDay = text(ocr, "birth_day");
        if (birthDay.isBlank()) birthDay = text(ocr, "dob");
        String address = text(ocr, "recent_location");
        if (address.isBlank()) address = text(ocr, "address");
        if (address.isBlank()) address = text(ocr, "origin_location");
        LocalDate expires = date(text(ocr, "valid_date"));

        JsonNode compare = object(compareResponse);

        // Danh mục giấy tờ VNPT cấu hình có thể trả mã số khác nhau (ví dụ 5 cho hồ sơ CCCD hiện hành).
        // Kết quả của nhà cung cấp là căn cứ cuối cùng; chỉ yêu cầu cả hai mặt đều có và được phân loại nhất quán.
        boolean ocrOk = success(ocrResponse)
                && "OK".equals(text(ocr, "msg"))
                && "OK".equals(text(ocr, "msg_back"))
                && ocr.path("type_id").isIntegralNumber()
                && ocr.path("back_type_id").isIntegralNumber()
                && ocr.path("type_id").asInt(-1) == ocr.path("back_type_id").asInt(-2)
                && number.matches("[0-9]{12}") && !name.isBlank();

        // VNPT có thể trả cảnh báo chất lượng ảnh dù OCR đã thành công. Gian lận hay chỉnh sửa có các trường riêng,
        // nên cảnh báo chụp chung chung không được biến một kết quả OCR hợp lệ thành thất bại.
        // Giao dịch thành công trên dashboard VNPT là căn cứ cuối cùng. Ứng dụng lưu thông tin
        // định danh từ OCR và không chạy thêm một phán quyết mâu thuẫn ở phía mình.
        boolean valid = ocrOk;
        if (!valid) {
            log.warn("VNPT OCR rejected locally: providerSuccess={}, msg={}, msgBack={}, typeId={}, backTypeId={}, idDigits={}, namePresent={}",
                    success(ocrResponse), text(ocr, "msg"), text(ocr, "msg_back"),
                    ocr.path("type_id").asText("missing"), ocr.path("back_type_id").asText("missing"),
                    number.matches("[0-9]{12}"), !name.isBlank());
        }

        double score;
        try { score = Double.parseDouble(compare.path("prob").asText()) / 100.0; }
        catch (RuntimeException ex) { score = Double.NaN; }
        boolean faceLive = success(face) && "success".equals(text(object(face), "liveness"))
                && "yes".equals(text(object(face), "is_eye_open"));
        boolean matched = success(compareResponse) && "MATCH".equals(text(compare, "msg"))
                && (!compare.has("multiple_faces") || isFalse(compare.path("multiple_faces")));

        return new VnptEkycClient.VerificationResult(reference, EkycDocumentType.ID_CARD,
                number, name, valid, liveCard(front) && liveCard(back), faceLive, matched, score, expires,
                birthDay, address);
    }
    /** Mặt thẻ qua kiểm tra thật giả: thành công, không ghép mặt, không giả liveness, không chụp lại từ ảnh in. */
    private boolean liveCard(JsonNode response) {
        JsonNode value = object(response);
        return success(response) && "success".equals(text(value, "liveness"))
                && isFalse(value.path("face_swapping")) && isFalse(value.path("fake_liveness"))
                && (!value.has("fake_print_photo") || isFalse(value.path("fake_print_photo")));
    }
    /** Đúng khi nút là giá trị boolean {@code false} (thiếu hoặc kiểu khác thì không tính). */
    private boolean isFalse(JsonNode node) { return node.isBoolean() && !node.booleanValue(); }
    /** Phản hồi của VNPT thành công khi {@code message} là {@code IDG-00000000}. */
    private boolean success(JsonNode node) {
        return node != null && "IDG-00000000".equals(text(node, "message"));
    }
    /** Lấy phần {@code object} của phản hồi; thiếu thì trả nút JSON rỗng để các lần đọc sau không gặp null. */
    private JsonNode object(JsonNode node) {
        return node != null && node.path("object").isObject() ? node.path("object") : mapper.createObjectNode();
    }
    /** Đọc trường văn bản đã bỏ khoảng trắng; thiếu hoặc không phải chuỗi thì trả chuỗi rỗng. */
    private String text(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).textValue().trim() : "";
    }
    /** Trường cờ không bị đánh dấu: rỗng hoặc {@code no}. */
    private boolean notFlagged(JsonNode parent, String field) {
        String value = text(parent, field);
        return value.isEmpty() || "no".equalsIgnoreCase(value);
    }
    /** Giấy tờ không bị báo chỉnh sửa: trường {@code is_legal} rỗng hoặc {@code yes}. */
    private boolean notIllegal(JsonNode tampering) {
        String value = text(tampering, "is_legal");
        return value.isEmpty() || "yes".equalsIgnoreCase(value);
    }
    /** Đọc ngày dạng {@code dd/MM/uuuu} theo chế độ nghiêm ngặt; sai định dạng thì trả null. */
    private LocalDate date(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)); }
        catch (RuntimeException ex) { return null; }
    }
}
