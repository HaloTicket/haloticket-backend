package com.haloticket.marketplace_service.dto;
import java.math.BigDecimal;
import java.util.List;
/**
 * Yêu cầu đăng bán lại vé: mã vé (hoặc danh sách vé), giá bán, phiên bản chính sách và điều khoản đã đồng ý,
 * cùng cờ cho phép bán lẻ từng vé.
 */
public record CreateListingRequest(String ticketId, BigDecimal price, Long policyVersion, String termsVersion,
                                   Boolean allowRetail, List<String> ticketIds) {
    /**
     * Constructor đăng bán một vé: không có danh sách {@code ticketIds}.
     *
     * @param ticketId mã vé cần bán
     * @param price giá bán
     * @param policyVersion phiên bản chính sách chuyển nhượng đang áp dụng
     * @param termsVersion phiên bản điều khoản người bán đã đồng ý
     * @param allowRetail cho phép bán lẻ từng vé hay không
     */
    public CreateListingRequest(String ticketId, BigDecimal price, Long policyVersion, String termsVersion, Boolean allowRetail) {
        this(ticketId, price, policyVersion, termsVersion, allowRetail, null);
    }
}
