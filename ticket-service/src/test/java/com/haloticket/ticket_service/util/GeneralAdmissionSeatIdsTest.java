package com.haloticket.ticket_service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GeneralAdmissionSeatIdsTest {

    @Test
    @DisplayName("GENERAL_ADMISSION should be recognized as GA")
    void testExactGeneralAdmission() {
        assertTrue(GeneralAdmissionSeatIds.isGeneralAdmission("GENERAL_ADMISSION"));
        assertTrue(GeneralAdmissionSeatIds.isGeneralAdmission("general_admission"));
        assertTrue(GeneralAdmissionSeatIds.isGeneralAdmission(" GENERAL_ADMISSION "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc-GA-1",
            "abc-STT-12",
            "e7351c0a-edc3-4757-ab1c-45be5e2e853d-STT-1",
            "tier123-GA-999",
            "zone_A-STT-0",
            "95bfcbd6-0b55-4961-84c7-8afd68ae57c9-AI-1794281846929-1", // what the customer chatbot sends (order HALO-1794282228280)
            "tier-AI-1794281846929-12"
    })
    @DisplayName("Valid GA or STT patterns should return true")
    void testValidGaPatterns(String seatId) {
        assertTrue(GeneralAdmissionSeatIds.isGeneralAdmission(seatId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "b8d2af58-0ac0-4261-b35e-f444e77b7467", // real UUID seat
            "seat-123",
            "A-12",
            "xSTT1",
            "GA",
            "STT",
            "-GA-1",
            "-STT-1",
            "abc-GA-",
            "abc-STT-",
            "abc-GA-xyz",
            "abcGA1",
            "contains-GA-inside-not-ending-with-number",
            "-AI-1794281846929-1",
            "tier-AI-1794281846929",
            "tier-AI-abc-1",
            "seat-AI-row-3"
    })
    @DisplayName("Invalid, empty, real seat UUID or malformed strings should return false")
    void testInvalidSeatIds(String seatId) {
        assertFalse(GeneralAdmissionSeatIds.isGeneralAdmission(seatId));
    }

    @Test
    @DisplayName("the ticket type id is taken from a chatbot standing-ticket id")
    void testExtractFromChatbotId() {
        assertEquals("95bfcbd6-0b55-4961-84c7-8afd68ae57c9",
                GeneralAdmissionSeatIds.extractTicketTypeId("95bfcbd6-0b55-4961-84c7-8afd68ae57c9-AI-1794281846929-1"));
        assertEquals("tier", GeneralAdmissionSeatIds.extractTicketTypeId("tier-AI-17-3"));
        assertNull(GeneralAdmissionSeatIds.extractTicketTypeId("seat-AI-row-3"));
    }

    @Test
    @DisplayName("null input should return false")
    void testNullSeatId() {
        assertFalse(GeneralAdmissionSeatIds.isGeneralAdmission(null));
    }

    @Test
    @DisplayName("extractTicketTypeId should extract tierId prefix properly")
    void testExtractTicketTypeId() {
        assertEquals("abc", GeneralAdmissionSeatIds.extractTicketTypeId("abc-GA-1"));
        assertEquals("tier-xyz", GeneralAdmissionSeatIds.extractTicketTypeId("tier-xyz-STT-12"));
        assertEquals("e7351c0a-edc3-4757-ab1c-45be5e2e853d",
                GeneralAdmissionSeatIds.extractTicketTypeId("e7351c0a-edc3-4757-ab1c-45be5e2e853d-STT-1"));
        assertNull(GeneralAdmissionSeatIds.extractTicketTypeId("GENERAL_ADMISSION"));
        assertNull(GeneralAdmissionSeatIds.extractTicketTypeId("seat-123"));
        assertNull(GeneralAdmissionSeatIds.extractTicketTypeId(null));
    }
}
