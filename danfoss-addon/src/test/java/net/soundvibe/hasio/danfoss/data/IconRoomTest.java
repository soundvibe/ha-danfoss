package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.model.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IconRoomTest {

    @Test
    void testStateJson() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 99);
        var state = sut.toState();

        System.out.println(Json.toJsonString(state));
    }

    @Test
    void shouldUnmarshalCommand() {
        var commandJson = """
                {"command": "setHomeTemperature","value":"23.5","roomNumber":"0"}""";

        var actual = Json.fromString(commandJson, Command.class);
        assertEquals(23.5, actual.value());
        assertEquals(0, actual.roomNumber());
        assertEquals("setHomeTemperature", actual.command());
    }
}