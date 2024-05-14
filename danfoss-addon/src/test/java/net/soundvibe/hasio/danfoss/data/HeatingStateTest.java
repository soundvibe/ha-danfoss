package net.soundvibe.hasio.danfoss.data;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HeatingStateTest {

    @Test
    void should_resolve_heating_state() {
        assertEquals(HeatingState.OFF, HeatingState.from(false, false));
        assertEquals(HeatingState.OFF, HeatingState.from(false, true));
        assertEquals(HeatingState.HEAT, HeatingState.from(true, false));
        assertEquals(HeatingState.COOL, HeatingState.from(true, true));
    }
}