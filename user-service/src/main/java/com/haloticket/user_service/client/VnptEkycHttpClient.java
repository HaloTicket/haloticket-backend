package com.haloticket.user_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.user_service.service.VnptEkycClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
public class VnptEkycHttpClient implements VnptEkycClient {
    private final RestClient restClient;
    private final VnptEkycResponseMapper responseMapper;
    private final String tokenId;
    private final String tokenKey;
    private final String accessToken;
    private final String uploadPath;
    private final String ocrPath;
    private final String faceLivenessPath;
    private final String faceComparePath;
    private final boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VnptEkycHttpClient(
            @Value("${ekyc.vnpt.base-url:https://api.idg.vnpt.vn}") String baseUrl,
            @Value("${ekyc.vnpt.token-id:}") String tokenId,
            @Value("${ekyc.vnpt.token-key:}") String tokenKey,
            @Value("${ekyc.vnpt.access-token:}") String accessToken,
            @Value("${ekyc.vnpt.upload-path:/file-service/v1/addFile}") String uploadPath,
            @Value("${ekyc.vnpt.ocr-path:/ai/v1/ocr/id}") String ocrPath,
            @Value("${ekyc.vnpt.face-liveness-path:/ai/v1/face/liveness}") String faceLivenessPath,
            @Value("${ekyc.vnpt.face-compare-path:/ai/v1/face/compare}") String faceComparePath,
            @Value("${ekyc.vnpt.enabled:false}") boolean enabled
    ) {
        var endpoint = URI.create(baseUrl);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("VNPT endpoint must use HTTPS");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.enabled = enabled;
        this.responseMapper = new VnptEkycResponseMapper(objectMapper);
        this.tokenId = tokenId;
        this.tokenKey = tokenKey;
        this.accessToken = accessToken;
        this.uploadPath = uploadPath;
        this.ocrPath = ocrPath;
        this.faceLivenessPath = faceLivenessPath;
        this.faceComparePath = faceComparePath;
    }

    @Override
    public VerificationResult verify(MultipartFile frontImage, MultipartFile backImage, MultipartFile faceImage) {
        requireReady();
        String session = UUID.randomUUID().toString();
        String token = session.replace("-", "");
        String frontHash = upload(frontImage, "cccd-front");
        String backHash = upload(backImage, "cccd-back");
        String faceHash = upload(faceImage, "face-portrait");

        JsonNode ocr = post(ocrPath, ocrRequest(frontHash, backHash, session, token));
        JsonNode faceLiveness = post(faceLivenessPath, faceLivenessRequest(faceHash, session, token));
        JsonNode faceCompare = post(faceComparePath, faceCompareRequest(frontHash, faceHash, session, token));
        return responseMapper.map(session, ocr, null, null, faceLiveness, faceCompare);
    }

    static Map<String, Object> faceLivenessRequest(String faceHash, String session, String token) {
        return Map.of("img", faceHash, "client_session", session, "token", token);
    }

    static Map<String, Object> faceCompareRequest(String frontHash, String faceHash, String session, String token) {
        return Map.of("img_front", frontHash, "img_face", faceHash, "client_session", session, "token", token);
    }

    static Map<String, Object> ocrRequest(String frontHash, String backHash, String session, String token) {
        return Map.of(
                "img_front", frontHash, "img_back", backHash, "type", 9,
                "validate_postcode", true, "crop_param", "0,0", "client_session", session, "token", token
        );
    }

    private String upload(MultipartFile file, String title) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return title + ("image/png".equals(file.getContentType()) ? ".png" : ".jpg");
                }
            };
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", resource);
            parts.add("title", title);
            parts.add("description", title);
            JsonNode response = parse(request(uploadPath)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(String.class));
            if (response == null || !"IDG-00000000".equals(response.path("message").asText())) throw providerError();
            String hash = firstText(response, "/object/hash");
            if (hash == null) throw providerError();
            return hash;
        } catch (IOException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "EKYC_IMAGE_READ_FAILED");
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        JsonNode response = parse(request(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        if (response == null) throw providerError();
        return response;
    }

    private RestClient.RequestBodySpec request(String path) {
        return restClient.post()
                .uri(path)
                .header("Token-id", tokenId)
                .header("Token-key", tokenKey)
                .header("mac-address", "HALOTICKET")
                .header("Authorization", accessToken.startsWith("Bearer ") ? accessToken : "Bearer " + accessToken);
    }

    private JsonNode parse(String body) {
        if (body == null || body.length() > 1_000_000) throw providerError();
        try { return objectMapper.readTree(body); }
        catch (IOException exception) { throw providerError(); }
    }

    private String firstText(JsonNode node, String... pointers) {
        if (node == null) return null;
        for (String pointer : pointers) {
            String value = node.at(pointer).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    @Override
    public void requireReady() {
        if (!enabled || tokenId.isBlank() || tokenKey.isBlank() || accessToken.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "EKYC_NOT_CONFIGURED");
        }
    }

    private ResponseStatusException providerError() {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, "EKYC_PROVIDER_UNAVAILABLE");
    }
}
