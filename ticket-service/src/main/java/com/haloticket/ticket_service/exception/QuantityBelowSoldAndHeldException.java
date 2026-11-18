package com.haloticket.ticket_service.exception;

/**
 * Ban tổ chức đặt số lượng vé phát hành thấp hơn tổng đã bán cộng đã giữ chỗ; việc đồng bộ bị hoàn tác.
 */
public class QuantityBelowSoldAndHeldException extends RuntimeException {

    private final String ticketTypeId;
    private final String ticketTypeName;
    private final int sold;
    private final int held;

    /**
     * @param ticketTypeId mã loại vé
     * @param ticketTypeName tên loại vé
     * @param sold số vé đã bán
     * @param held số vé đang giữ chỗ
     */
    public QuantityBelowSoldAndHeldException(String ticketTypeId, String ticketTypeName, int sold, int held) {
        super("QUANTITY_BELOW_SOLD_AND_HELD");
        this.ticketTypeId = ticketTypeId;
        this.ticketTypeName = ticketTypeName;
        this.sold = sold;
        this.held = held;
    }

    /** Mã loại vé bị vi phạm. */
    public String getTicketTypeId() { return ticketTypeId; }

    /** Tên loại vé bị vi phạm. */
    public String getTicketTypeName() { return ticketTypeName; }

    /** Số vé đã bán. */
    public int getSold() { return sold; }

    /** Số vé đang được giữ chỗ. */
    public int getHeld() { return held; }

    /** Số lượng phát hành tối thiểu được phép, bằng đã bán cộng đã giữ chỗ. */
    public int getMinAllowed() { return sold + held; }
}
