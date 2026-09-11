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

/** VNPT API contract: /admin-dashboard/vi/documents/api, read 2026-09-11.
 * Only explicit successful, consistent CCCD results can be accepted.
 */
public class VnptEkycResponseMapper {
    private static final Logger log = LoggerFactory.getLogger(VnptEkycResponseMapper.class);
    private final ObjectMapper mapper;
    public VnptEkycResponseMapper(ObjectMapper mapper) { this.mapper = mapper; }

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

        // VNPT's configured document catalog can return different numeric IDs (for example 5
        // for the current CCCD profile). The provider result is authoritative; require only
        // that both sides are present and classified consistently.
        boolean ocrOk = success(ocrResponse)
                && "OK".equals(text(ocr, "msg"))
                && "OK".equals(text(ocr, "msg_back"))
                && ocr.path("type_id").isIntegralNumber()
                && ocr.path("back_type_id").isIntegralNumber()
                && ocr.path("type_id").asInt(-1) == ocr.path("back_type_id").asInt(-2)
                && number.matches("[0-9]{12}") && !name.isBlank();

        // VNPT may return quality warnings even when OCR completed successfully. Fraud/tampering
        // has dedicated fields, so generic capture warnings must not turn a valid OCR into failure.
        // A successful VNPT dashboard transaction is the source of truth. The application
        // stores the OCR identity and does not run a second, conflicting verdict locally.
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
