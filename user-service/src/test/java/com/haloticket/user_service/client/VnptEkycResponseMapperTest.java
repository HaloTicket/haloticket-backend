package com.haloticket.user_service.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class VnptEkycResponseMapperTest {
    private final VnptEkycResponseMapper mapper = new VnptEkycResponseMapper(new ObjectMapper());

    @Test
    void mapsSuccessfulProviderResponsesIntoNormalizedDecisionInput() throws Exception {
        var ocr = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"id":"079123456789","name":"NGUYEN VAN A",
                "msg":"OK","msg_back":"OK","type_id":1,"back_type_id":1,"valid_date":"01/01/2036",
                "expire_warning":"no","back_expire_warning":"no","id_fake_warning":"no",
                "tampering":{"is_legal":"yes","warning":[]}}}
                """);
        var card = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"liveness":"success","face_swapping":false,"fake_liveness":false}}
                """);
        var face = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"liveness":"success","is_eye_open":"yes"}}
                """);
        var compare = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"msg":"MATCH","prob":97.4}}
                """);

        var result = mapper.map("session-1", ocr, card, card, face, compare);

        assertThat(result.documentNumber()).isEqualTo("079123456789");
        assertThat(result.legalName()).isEqualTo("NGUYEN VAN A");
        assertThat(result.documentValid()).isTrue();
        assertThat(result.documentLivenessPassed()).isTrue();
        assertThat(result.faceLivenessPassed()).isTrue();
        assertThat(result.faceMatched()).isTrue();
        assertThat(result.matchScore()).isCloseTo(0.974, offset(0.000001));
    }

    @Test
    void missingOrNegativeProviderFieldsNeverBecomeSuccessful() throws Exception {
        var empty = new ObjectMapper().readTree("{}");
        var fail = new ObjectMapper().readTree("""
                {"object":{"liveness":"fail","msg":"NOT_MATCH","prob":0.99}}
                """);

        var result = mapper.map("session-2", empty, fail, fail, fail, fail);

        assertThat(result.documentValid()).isFalse();
        assertThat(result.documentLivenessPassed()).isFalse();
        assertThat(result.faceLivenessPassed()).isFalse();
        assertThat(result.faceMatched()).isFalse();
        assertThat(result.matchScore()).isCloseTo(0.0099, offset(0.000001));
    }

    @Test
    void rejectsDocumentWhenTamperingIsLegalIsNo() throws Exception {
        var ocr = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"id":"079123456789","name":"NGUYEN VAN A",
                "tampering":{"is_legal":"no","warning":["input_khong_hop_le"]}}}
                """);
        var card = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"liveness":"success"}}
                """);

        var result = mapper.map("session-3", ocr, card, card, null, null);

        assertThat(result.documentValid()).isFalse();
    }

    @Test
    void missingLivenessAndFaceChecksNeverBecomeSuccessful() throws Exception {
        var ocr = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"id":"079123456789","name":"NGUYEN VAN A",
                "msg":"OK","msg_back":"OK","type_id":1,"back_type_id":1,"valid_date":"01/01/2036",
                "expire_warning":"no","back_expire_warning":"no","id_fake_warning":"no",
                "tampering":{"is_legal":"yes","warning":[]}}}
                """);

        var result = mapper.map("session-4", ocr, null, null, null, null);

        assertThat(result.documentLivenessPassed()).isFalse();
        assertThat(result.faceLivenessPassed()).isFalse();
        assertThat(result.faceMatched()).isFalse();
        assertThat(result.matchScore()).isNaN();
    }

    @Test
    void acceptsSuccessfulCccdOcrWhenOptionalValidationFieldsAreAbsent() throws Exception {
        var ocr = new ObjectMapper().readTree("""
                {"message":"IDG-00000000","object":{"id":"079123456789","name":"NGUYEN VAN A",
                "msg":"OK","msg_back":"OK","type_id":1,"back_type_id":1}}
                """);

        var result = mapper.map("session-5", ocr, null, null, null, null);

        assertThat(result.documentValid()).isTrue();
    }
}
