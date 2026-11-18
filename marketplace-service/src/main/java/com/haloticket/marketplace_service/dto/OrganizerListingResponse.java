package com.haloticket.marketplace_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Thông tin một tin bán lại hiển thị cho ban tổ chức: giá, khoảng giá cho phép lúc đăng,
 * phí dịch vụ, lý do kiểm duyệt. Email người bán đã được che bớt.
 */
public record OrganizerListingResponse(
    String listingId,
    String refCode,
    String ticketTypeName,
    String seatCode,
    String sellerEmailMasked,
    BigDecimal price,
    String currency,
    String status,
    Instant createdAt,
    int quantity,
    List<String> ticketIds,
    BigDecimal originalPrice,
    BigDecimal minPriceSnapshot,
    BigDecimal maxPriceSnapshot,
    BigDecimal serviceFeePercent,
    String moderationReason
) {
    /**
     * Constructor có khoảng giá nhưng chưa có phí dịch vụ và lý do kiểm duyệt.
     *
     * @param listingId mã tin
     * @param refCode mã tham chiếu
     * @param ticketTypeName tên loại vé
     * @param seatCode mã chỗ ngồi
     * @param sellerEmailMasked email người bán đã che
     * @param price giá bán
     * @param currency đơn vị tiền tệ
     * @param status trạng thái tin
     * @param createdAt thời điểm tạo
     * @param quantity số vé
     * @param ticketIds danh sách mã vé
     * @param originalPrice giá gốc
     * @param minPriceSnapshot giá tối thiểu tại thời điểm đăng
     * @param maxPriceSnapshot giá tối đa tại thời điểm đăng
     */
    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status,
                                    Instant createdAt, int quantity, List<String> ticketIds, BigDecimal originalPrice,
                                    BigDecimal minPriceSnapshot, BigDecimal maxPriceSnapshot) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt,
                quantity, ticketIds, originalPrice, minPriceSnapshot, maxPriceSnapshot, null, null);
    }

    /**
     * Constructor rút gọn: mặc định 1 vé, danh sách vé rỗng, các trường giá và kiểm duyệt bổ sung để {@code null}.
     *
     * @param listingId mã tin
     * @param refCode mã tham chiếu
     * @param ticketTypeName tên loại vé
     * @param seatCode mã chỗ ngồi
     * @param sellerEmailMasked email người bán đã che
     * @param price giá bán
     * @param currency đơn vị tiền tệ
     * @param status trạng thái tin
     * @param createdAt thời điểm tạo
     */
    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status, Instant createdAt) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt, 1, List.of(), null, null, null, null, null);
    }

    /**
     * Constructor có số lượng và danh sách vé; các trường giá và kiểm duyệt bổ sung để {@code null}.
     *
     * @param listingId mã tin
     * @param refCode mã tham chiếu
     * @param ticketTypeName tên loại vé
     * @param seatCode mã chỗ ngồi
     * @param sellerEmailMasked email người bán đã che
     * @param price giá bán
     * @param currency đơn vị tiền tệ
     * @param status trạng thái tin
     * @param createdAt thời điểm tạo
     * @param quantity số vé
     * @param ticketIds danh sách mã vé
     */
    public OrganizerListingResponse(String listingId, String refCode, String ticketTypeName, String seatCode,
                                    String sellerEmailMasked, BigDecimal price, String currency, String status,
                                    Instant createdAt, int quantity, List<String> ticketIds) {
        this(listingId, refCode, ticketTypeName, seatCode, sellerEmailMasked, price, currency, status, createdAt, quantity, ticketIds, null, null, null, null, null);
    }
}
