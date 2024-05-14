package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.model.Command;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IconRoomTest {

    private static final Logger logger = LoggerFactory.getLogger(IconRoomTest.class);

    @Test
    void test_state_json() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 19.0,
                 30.0, 15.0, (short) 99, HeatingState.OFF, RoomMode.HOME);
        var state = sut.toState();

        logger.info(Json.toJsonString(state));
    }

    @Test
    void should_unmarshal_command() {
        var commandJson = """
                {"command": "setHomeTemperature","value":"23.5","roomNumber":"0"}""";

        var actual = Json.fromString(commandJson, Command.class);
        assertEquals(23.5, actual.value());
        assertEquals(0, actual.roomNumber());
        assertEquals("setHomeTemperature", actual.command());
    }
}