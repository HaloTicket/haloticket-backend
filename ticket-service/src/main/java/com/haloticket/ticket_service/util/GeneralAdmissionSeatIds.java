package com.haloticket.ticket_service.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeneralAdmissionSeatIds {

    private static final String EXACT_GA = "GENERAL_ADMISSION";
    private static final Pattern GA_STT_PATTERN = Pattern.compile("^.+-(GA|STT)-\\d+$");
    private static final Pattern EXTRACT_TYPE_ID_PATTERN = Pattern.compile("^(.+)-(GA|STT)-\\d+$");

    private GeneralAdmissionSeatIds() {
        // utility class
    }

    /**
     * Determines whether the given seatId represents a General Admission (GA) / non-seatmap virtual seat.
     * Matches "GENERAL_ADMISSION" (case-insensitive) or regex ^.+-(GA|STT)-\d+$.
     *
     * @param seatId the seatId string to check
     * @return true if GA or STT format, false otherwise
     */
    public static boolean isGeneralAdmission(String seatId) {
        if (seatId == null) {
            return false;
        }
        String trimmed = seatId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (EXACT_GA.equalsIgnoreCase(trimmed)) {
            return true;
        }
        return GA_STT_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Extracts the ticketTypeId prefix from a virtual GA seatId if present.
     * Example: "e7351c0a-edc3-4757-ab1c-45be5e2e853d-STT-1" -> "e7351c0a-edc3-4757-ab1c-45be5e2e853d"
     *
     * @param seatId the seatId string
     * @return extracted ticketTypeId if format matches, or null
     */
    public static String extractTicketTypeId(String seatId) {
        if (seatId == null) {
            return null;
        }
        String trimmed = seatId.trim();
        Matcher matcher = EXTRACT_TYPE_ID_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}
