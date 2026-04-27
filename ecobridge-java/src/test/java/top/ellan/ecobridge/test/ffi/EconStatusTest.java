package top.ellan.ecobridge.test.ffi;

import org.junit.jupiter.api.Test;
import top.ellan.ecobridge.infrastructure.ffi.bridge.EconStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EconStatus — the cross-language communication status protocol.
 */
class EconStatusTest {

    @Test
    void testFromCodeReturnsCorrectStatus() {
        assertEquals(EconStatus.OK, EconStatus.from(0));
        assertEquals(EconStatus.NULL_POINTER, EconStatus.from(1));
        assertEquals(EconStatus.INVALID_LENGTH, EconStatus.from(2));
        assertEquals(EconStatus.INVALID_VALUE, EconStatus.from(3));
        assertEquals(EconStatus.NUMERIC_OVERFLOW, EconStatus.from(10));
        assertEquals(EconStatus.INTERNAL_ERROR, EconStatus.from(100));
        assertEquals(EconStatus.PANIC, EconStatus.from(101));
        assertEquals(EconStatus.FATAL, EconStatus.from(255));
    }

    @Test
    void testUnknownCodeFallsBackToInternalError() {
        assertEquals(EconStatus.INTERNAL_ERROR, EconStatus.from(999));
        assertEquals(EconStatus.INTERNAL_ERROR, EconStatus.from(-1));
    }

    @Test
    void testIsOkReturnsTrueOnlyForOk() {
        assertTrue(EconStatus.OK.isOk());
        assertFalse(EconStatus.NULL_POINTER.isOk());
        assertFalse(EconStatus.PANIC.isOk());
        assertFalse(EconStatus.FATAL.isOk());
        assertFalse(EconStatus.INTERNAL_ERROR.isOk());
    }

    @Test
    void testCheckDoesNotThrowForOk() {
        assertDoesNotThrow(() -> EconStatus.OK.check("test context"));
    }

    @Test
    void testCodeValuesAreStable() {
        assertEquals(0, EconStatus.OK.code());
        assertEquals(1, EconStatus.NULL_POINTER.code());
        assertEquals(255, EconStatus.FATAL.code());
    }

    @Test
    void testAllStatusesHaveUniqueCodes() {
        EconStatus[] values = EconStatus.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(values[i].code(), values[j].code(),
                    values[i].name() + " and " + values[j].name() + " should have different codes");
            }
        }
    }
}
