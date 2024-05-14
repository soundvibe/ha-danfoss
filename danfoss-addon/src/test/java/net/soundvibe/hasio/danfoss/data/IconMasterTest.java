package net.soundvibe.hasio.danfoss.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IconMasterTest {

    @Test
    void test_state_json() {
        var sut = new IconMaster("Test House", 17.0, 21.0, "1.0", "21.0", "0",
                1, 1, Instant.parse("2023-12-03T10:15:30.00Z"));
        var state = sut.toState();
        Assertions.assertNotNull(state);
    }

}