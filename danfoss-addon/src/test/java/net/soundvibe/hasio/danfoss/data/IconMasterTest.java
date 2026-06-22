package net.soundvibe.hasio.danfoss.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IconMasterTest {

    @Test
    void test_state_json() {
        var sut = new IconMaster("Test House", 17.0, 21.0, "1.0", "21.0", "0",
                1, 1, Instant.parse("2023-12-03T10:15:30.00Z"));
        var state = sut.toState();
        assertNotNull(state);
    }

    @Test
    void test_state_json_with_null_fields() {
        var sut = new IconMaster(null, 0.0, 0.0, null, null, null,
                0, 0, null);
        var state = sut.toState();
        assertNotNull(state);
        var attributes = state.attributes();
        assertEquals("null", attributes.get("house_name"));
        assertEquals("null", attributes.get("hardware_revision"));
        assertEquals("null", attributes.get("software_revision"));
        assertEquals("null", attributes.get("serial_number"));
        assertEquals("null", attributes.get("production_date"));
    }

}