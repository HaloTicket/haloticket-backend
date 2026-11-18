package com.haloticket.event_service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Phản hồi 422 {code, ticketTypeId, name, sold, held, minAllowed} từ ticket-service được đổi thành {@link TicketSyncRejectedException}; mã trạng thái khác giao cho bộ giải mã mặc định. */
public class TicketServiceErrorDecoder implements ErrorDecoder {
    private static final String DEFAULT_CODE = "TICKET_SYNC_REJECTED";
    private static final String[] DETAIL_KEYS = {"ticketTypeId", "name", "sold", "held", "minAllowed"};

    private final ObjectMapper mapper = new ObjectMapper();
    private final ErrorDecoder fallback = new ErrorDecoder.Default();

    /**
     * Đổi phản hồi lỗi của ticket-service thành ngoại lệ phù hợp.
     * Với 422, lấy {@code code} và các khóa chi tiết có giá trị trong thân phản hồi; thiếu {@code code} thì dùng {@code TICKET_SYNC_REJECTED}.
     * @param methodKey khóa của phương thức Feign đã gọi
     * @param response phản hồi lỗi nhận được
     * @return {@code TicketSyncRejectedException} cho 422, ngoại lệ mặc định của Feign cho mã khác
     */
    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() != 422) {
            return fallback.decode(methodKey, response);
        }
        Map<String, Object> body = readBody(response);
        Object code = body.get("code");
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : DETAIL_KEYS) {
            if (body.get(key) != null) details.put(key, body.get(key));
        }
        return new TicketSyncRejectedException(code == null ? DEFAULT_CODE : String.valueOf(code), details, null);
    }

    /**
     * Đọc thân phản hồi JSON thành map.
     * @param response phản hồi cần đọc
     * @return nội dung thân phản hồi; map rỗng nếu không có thân hoặc không đọc được
     */
    private Map<String, Object> readBody(Response response) {
        if (response.body() == null) return Map.of();
        try (InputStream in = response.body().asInputStream()) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
