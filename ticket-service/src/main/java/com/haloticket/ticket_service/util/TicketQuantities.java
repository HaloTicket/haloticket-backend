package com.haloticket.ticket_service.util;

/**
 * The four numbers a client shows for a ticket type, derived from the three stored columns
 * (initial_quantity, total_quantity, reserved_quantity). total_quantity is what is left in stock, held or not.
 */
public final class TicketQuantities {

    private TicketQuantities() {
    }

    /** Still in stock, including what unpaid bookings hold. */
    public static Integer remaining(Integer total) {
        return total;
    }

    /** Held by unpaid general-admission bookings; never negative, null counts as 0. */
    public static int held(Integer reserved) {
        return reserved == null ? 0 : Math.max(0, reserved);
    }

    /** What can still be bought now: remaining minus held, never negative. */
    public static Integer available(Integer total, Integer reserved) {
        return total == null ? null : Math.max(0, total - held(reserved));
    }

    /**
     * Sold so far: initial minus remaining, never negative. Null (unknown) when either number is missing, which is
     * the case of old rows created before initial_quantity existed; clients must not show 0 for it.
     */
    public static Integer sold(Integer initial, Integer total) {
        return initial == null || total == null ? null : Math.max(0, initial - total);
    }
}
