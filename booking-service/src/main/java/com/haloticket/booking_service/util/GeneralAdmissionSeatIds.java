package com.haloticket.booking_service.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the virtual seat ids that older clients send for general-admission tickets. Mirrors
 * ticket-service's GeneralAdmissionSeatIds so both services agree on what a virtual seat is.
 */
public final class GeneralAdmissionSeatIds {

    private static final String EXACT_GA = "GENERAL_ADMISSION";
    private static final Pattern GA_STT_PATTERN = Pattern.compile("^(.+)-(GA|STT)-\\d+$");

    private GeneralAdmissionSeatIds() {
    }

    public static boolean isGeneralAdmission(String seatId) {
        if (seatId == null) return false;
        String trimmed = seatId.trim();
        if (trimmed.isEmpty()) return false;
        return EXACT_GA.equalsIgnoreCase(trimmed) || GA_STT_PATTERN.matcher(trimmed).matches();
    }

    /** The ticket type (or zone) prefix of "<id>-STT-<n>" / "<id>-GA-<n>", or null. */
    public static String extractTicketTypeId(String seatId) {
        if (seatId == null) return null;
        Matcher matcher = GA_STT_PATTERN.matcher(seatId.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
