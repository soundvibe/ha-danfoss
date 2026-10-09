package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.model.Command;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

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
    void should_report_battery_level_when_battery_indication_was_reported() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 19.0,
                30.0, 15.0, (short) 99, HeatingState.OFF, RoomMode.HOME);

        assertTrue(sut.hasBattery());
        assertEquals("99", sut.toState().attributes().get("battery_level"));
    }

    @Test
    void should_not_report_battery_when_no_battery_indication_was_reported() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 19.0,
                30.0, 15.0, (short) 0, HeatingState.OFF, RoomMode.HOME);

        assertFalse(sut.hasBattery());
    }

    @Test
    void should_generate_battery_sensor_discovery_json() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 19.0,
                30.0, 15.0, (short) 99, HeatingState.OFF, RoomMode.HOME);
        var iconMaster = new IconMaster("House", 21.0, 10.0, "1.0", "2.0", "12345", 1, 0, null);

        var sensor = sut.toMQTTBatterySensorEntity("danfoss_icon_thermostat_room_1_battery", "danfoss/icon/%d/state", iconMaster);

        assertEquals("Living Room battery", sensor.name());
        assertEquals("danfoss/icon/1/state", sensor.state_topic());
        assertEquals("{{ value_json.attributes.battery_level }}", sensor.value_template());
        assertEquals("battery", sensor.device_class());
        assertEquals("measurement", sensor.state_class());
        assertEquals("%", sensor.unit_of_measurement());
        assertEquals("diagnostic", sensor.entity_category());
        assertEquals("12345", sensor.device().get("identifiers"));
        logger.info(Json.toJsonString(sensor));
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