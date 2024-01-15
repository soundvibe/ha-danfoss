package net.soundvibe;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.model.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StdInListenerTest {

    @Test
    void shouldParseCommand() {
        var expected = new Command("setHomeTemperature", 1, 23.1);
        var input = """
                { "command": "setHomeTemperature", "value": 23.1, "roomNumber": 1 }""";

        var actual = Json.fromString(input, Command.class);
        assertEquals(expected, actual);
    }
}