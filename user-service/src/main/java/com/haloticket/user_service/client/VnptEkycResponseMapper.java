package com.haloticket.user_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.user_service.enums.EkycDocumentType;
import com.haloticket.user_service.service.VnptEkycClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** VNPT API contract: /admin-dashboard/vi/documents/api, read 2026-09-11.
 * Only explicit successful, consistent CCCD results can be accepted.
 */
public class VnptEkycResponseMapper {
    private final ObjectMapper mapper;
    public VnptEkycResponseMapper(ObjectMapper mapper) { this.mapper = mapper; }

    public VnptEkycClient.VerificationResult map(String reference, JsonNode ocrResponse,
            JsonNode front, JsonNode back, JsonNode face, JsonNode compareResponse) {
        JsonNode ocr = object(ocrResponse);
        String number = text(ocr, "id");
        String name = text(ocr, "name");
        LocalDate expires = date(text(ocr, "valid_date"));

        JsonNode compare = object(compareResponse);

        // OCR must succeed and document must be the correct type (chip CCCD = type_id 1)
        boolean ocrOk = success(ocrResponse)
                && "OK".equals(text(ocr, "msg"))
                && "OK".equals(text(ocr, "msg_back"))
                && ocr.path("type_id").isIntegralNumber() && ocr.path("type_id").asInt(-1) == 1
                && ocr.path("back_type_id").isIntegralNumber() && ocr.path("back_type_id").asInt(-1) == 1
                && number.matches("[0-9]{12}") && !name.isBlank();

        // VNPT may return quality warnings even when OCR completed successfully. Fraud/tampering
        // has dedicated fields, so generic capture warnings must not turn a valid OCR into failure.
        boolean noSecurityFlags = notFlagged(ocr, "id_fake_warning")
                && notIllegal(ocr.path("tampering"));

        boolean valid = ocrOk && noSecurityFlags;

        double score;
        try { score = Double.parseDouble(compare.path("prob").asText()) / 100.0; }
        catch (RuntimeException ex) { score = Double.NaN; }
        boolean faceLive = success(face) && "success".equals(text(object(face), "liveness"))
                && "yes".equals(text(object(face), "is_eye_open"));
        boolean matched = success(compareResponse) && "MATCH".equals(text(compare, "msg"))
                && (!compare.has("multiple_faces") || isFalse(compare.path("multiple_faces")));

        return new VnptEkycClient.VerificationResult(reference, EkycDocumentType.ID_CARD,
                number, name, valid, liveCard(front) && liveCard(back), faceLive, matched, score, expires);
    }
    private boolean liveCard(JsonNode response) {
        JsonNode value = object(response);
        return success(response) && "success".equals(text(value, "liveness"))
                && isFalse(value.path("face_swapping")) && isFalse(value.path("fake_liveness"))
                && (!value.has("fake_print_photo") || isFalse(value.path("fake_print_photo")));
    }
    private boolean isFalse(JsonNode node) { return node.isBoolean() && !node.booleanValue(); }
    private boolean success(JsonNode node) {
        return node != null && "IDG-00000000".equals(text(node, "message"));
    }
    private JsonNode object(JsonNode node) {
        return node != null && node.path("object").isObject() ? node.path("object") : mapper.createObjectNode();
    }
    private String text(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).textValue().trim() : "";
    }
    private boolean notFlagged(JsonNode parent, String field) {
        String value = text(parent, field);
        return value.isEmpty() || "no".equalsIgnoreCase(value);
    }
    private boolean notIllegal(JsonNode tampering) {
        String value = text(tampering, "is_legal");
        return value.isEmpty() || "yes".equalsIgnoreCase(value);
    }
    private LocalDate date(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)); }
        catch (RuntimeException ex) { return null; }
    }
}
