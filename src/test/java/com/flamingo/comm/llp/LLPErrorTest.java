package com.flamingo.comm.llp;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLPErrorTest {

    @Test
    void codesAreTheWireValues() {
        assertEquals(1, LLPError.CRC.code());
        assertEquals(2, LLPError.LEN.code());
        assertEquals(3, LLPError.TIMEOUT.code());
        assertEquals(4, LLPError.SYNC.code());
    }

    @Test
    void wireNamesMatchTheConformanceVectors() {
        assertEquals("LLP_ERR_CRC", LLPError.CRC.wireName());
        assertEquals("LLP_ERR_LEN", LLPError.LEN.wireName());
        assertEquals("LLP_ERR_TIMEOUT", LLPError.TIMEOUT.wireName());
        assertEquals("LLP_ERR_SYNC", LLPError.SYNC.wireName());
    }

    @Test
    void fromCodeRoundTrips() {
        for (LLPError error : LLPError.values()) {
            assertEquals(error, LLPError.fromCode(error.code()));
            assertEquals(error, LLPError.fromWireName(error.wireName()));
        }
    }

    @Test
    void maybeFromCodeIsEmptyForUnknownValues() {
        assertEquals(Optional.empty(), LLPError.maybeFromCode(0));
        assertEquals(Optional.empty(), LLPError.maybeFromCode(5));
        assertEquals(Optional.of(LLPError.SYNC), LLPError.maybeFromCode(4));
    }

    @Test
    void unknownCodesAndNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LLPError.fromCode(0));
        assertThrows(IllegalArgumentException.class, () -> LLPError.fromCode(7));
        assertThrows(IllegalArgumentException.class, () -> LLPError.fromWireName("CHECKSUM"));
    }

    @Test
    void descriptionsArePresent() {
        for (LLPError error : LLPError.values()) {
            assertTrue(error.description().length() > 5, error + " needs a description");
        }
    }
}
