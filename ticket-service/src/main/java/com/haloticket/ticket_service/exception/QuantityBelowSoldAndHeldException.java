package com.haloticket.ticket_service.exception;

/** An organizer tried to set the issued quantity below what is already sold plus held; the sync is rolled back. */
public class QuantityBelowSoldAndHeldException extends RuntimeException {

    private final String ticketTypeId;
    private final String ticketTypeName;
    private final int sold;
    private final int held;

    public QuantityBelowSoldAndHeldException(String ticketTypeId, String ticketTypeName, int sold, int held) {
        super("QUANTITY_BELOW_SOLD_AND_HELD");
        this.ticketTypeId = ticketTypeId;
        this.ticketTypeName = ticketTypeName;
        this.sold = sold;
        this.held = held;
    }

    public String getTicketTypeId() { return ticketTypeId; }

    public String getTicketTypeName() { return ticketTypeName; }

    public int getSold() { return sold; }

    public int getHeld() { return held; }

    public int getMinAllowed() { return sold + held; }
}
