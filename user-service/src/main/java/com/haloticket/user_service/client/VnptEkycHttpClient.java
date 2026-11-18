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

/**
 * Client HTTP gọi dịch vụ eKYC của VNPT: tải ảnh CCCD và ảnh chân dung lên, rồi gọi OCR, kiểm tra người thật (liveness)
 * và so khớp khuôn mặt. Kết quả được {@code VnptEkycResponseMapper} chuyển thành {@code VerificationResult}.
 */
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

    /**
     * Dựng client với cấu hình từ thuộc tính {@code ekyc.vnpt.*}. Chỉ chấp nhận địa chỉ HTTPS, không chứa thông tin đăng nhập;
     * thời gian chờ kết nối 5 giây, đọc 15 giây.
     *
     * @param baseUrl địa chỉ gốc của API VNPT
     * @param tokenId giá trị header {@code Token-id}
     * @param tokenKey giá trị header {@code Token-key}
     * @param accessToken token truy cập (tự thêm tiền tố {@code Bearer} nếu thiếu)
     * @param uploadPath đường dẫn API tải tệp
     * @param ocrPath đường dẫn API OCR giấy tờ
     * @param faceLivenessPath đường dẫn API kiểm tra người thật
     * @param faceComparePath đường dẫn API so khớp khuôn mặt
     * @param enabled bật hay tắt tích hợp eKYC
     * @throws IllegalArgumentException khi địa chỉ không phải HTTPS hợp lệ
     */
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

    /**
     * Chạy một lượt xác minh: tải ba ảnh lên VNPT, gọi OCR, kiểm tra người thật rồi so khớp ảnh mặt với ảnh mặt trước CCCD.
     * Mỗi lượt dùng một mã phiên ngẫu nhiên.
     *
     * @param frontImage ảnh mặt trước CCCD
     * @param backImage ảnh mặt sau CCCD
     * @param faceImage ảnh chân dung
     * @return kết quả xác minh đã ánh xạ
     * @throws ResponseStatusException 503 khi eKYC chưa cấu hình, không đọc được ảnh hoặc VNPT lỗi
     */
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

    /** Dựng nội dung yêu cầu kiểm tra người thật từ mã ảnh mặt đã tải lên. */
    static Map<String, Object> faceLivenessRequest(String faceHash, String session, String token) {
        return Map.of("img", faceHash, "client_session", session, "token", token);
    }

    /** Dựng nội dung yêu cầu so khớp giữa ảnh mặt trước CCCD và ảnh chân dung đã tải lên. */
    static Map<String, Object> faceCompareRequest(String frontHash, String faceHash, String session, String token) {
        return Map.of("img_front", frontHash, "img_face", faceHash, "client_session", session, "token", token);
    }

    /** Dựng nội dung yêu cầu OCR cho CCCD (loại giấy tờ 9) từ mã ảnh hai mặt đã tải lên. */
    static Map<String, Object> ocrRequest(String frontHash, String backHash, String session, String token) {
        return Map.of(
                "img_front", frontHash, "img_back", backHash, "type", 9,
                "validate_postcode", true, "crop_param", "0,0", "client_session", session, "token", token
        );
    }

    /**
     * Tải một ảnh lên dịch vụ tệp của VNPT.
     *
     * @param file ảnh cần tải
     * @param title tiêu đề, cũng là tên tệp và mô tả
     * @return mã băm của tệp do VNPT trả về
     * @throws ResponseStatusException 503 khi không đọc được ảnh hoặc VNPT không trả thành công
     */
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

    /** Gửi yêu cầu JSON tới một API của VNPT và trả về phản hồi đã phân tích; phản hồi rỗng bị coi là lỗi nhà cung cấp. */
    private JsonNode post(String path, Map<String, Object> body) {
        JsonNode response = parse(request(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
        if (response == null) throw providerError();
        return response;
    }

    /** Tạo yêu cầu POST tới đường dẫn kèm các header xác thực của VNPT. */
    private RestClient.RequestBodySpec request(String path) {
        return restClient.post()
                .uri(path)
                .header("Token-id", tokenId)
                .header("Token-key", tokenKey)
                .header("mac-address", "HALOTICKET")
                .header("Authorization", accessToken.startsWith("Bearer ") ? accessToken : "Bearer " + accessToken);
    }

    /** Phân tích chuỗi JSON phản hồi; rỗng, lớn hơn 1.000.000 ký tự hoặc không phải JSON thì coi là lỗi nhà cung cấp. */
    private JsonNode parse(String body) {
        if (body == null || body.length() > 1_000_000) throw providerError();
        try { return objectMapper.readTree(body); }
        catch (IOException exception) { throw providerError(); }
    }

    /**
     * Lấy giá trị văn bản không rỗng đầu tiên theo danh sách JSON pointer.
     *
     * @return giá trị đã bỏ khoảng trắng, hoặc null nếu không có
     */
    private String firstText(JsonNode node, String... pointers) {
        if (node == null) return null;
        for (String pointer : pointers) {
            String value = node.at(pointer).asText("").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    /**
     * Bảo đảm tích hợp eKYC đã bật và đủ thông tin xác thực.
     *
     * @throws ResponseStatusException 503 {@code EKYC_NOT_CONFIGURED} khi chưa sẵn sàng
     */
    @Override
    public void requireReady() {
        if (!enabled || tokenId.isBlank() || tokenKey.isBlank() || accessToken.isBlank()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "EKYC_NOT_CONFIGURED");
        }
    }

    /** Tạo lỗi 503 {@code EKYC_PROVIDER_UNAVAILABLE} dùng khi VNPT trả phản hồi không dùng được. */
    private ResponseStatusException providerError() {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, "EKYC_PROVIDER_UNAVAILABLE");
    }
}
