package com.haloticket.event_service.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 422 {code, ticketTypeId, name, sold, held, minAllowed} from ticket-service becomes {@link TicketSyncRejectedException}. */
public class TicketServiceErrorDecoder implements ErrorDecoder {
    private static final String DEFAULT_CODE = "TICKET_SYNC_REJECTED";
    private static final String[] DETAIL_KEYS = {"ticketTypeId", "name", "sold", "held", "minAllowed"};

    private final ObjectMapper mapper = new ObjectMapper();
    private final ErrorDecoder fallback = new ErrorDecoder.Default();

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

    private Map<String, Object> readBody(Response response) {
        if (response.body() == null) return Map.of();
        try (InputStream in = response.body().asInputStream()) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
