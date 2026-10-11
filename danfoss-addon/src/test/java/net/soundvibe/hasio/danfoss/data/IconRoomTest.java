package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.model.Command;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IconRoomTest {

    @ParameterizedTest
    @EnumSource(value = RoomMode.class, names = {"HOME", "AWAY", "SLEEP"})
    void discovery_has_one_active_setpoint_and_preserves_entity_identity(RoomMode preset) {
        var room = new IconRoom("Living Room", 1, 22.3, 23.5, 15.0, 19.0,
                35.0, 5.0, (short) 99, HeatingState.OFF, preset);
        var master = new IconMaster("House", 21.0, 10.0, "1.0", "2.0", "12345", 1, 0, null);
        var entity = room.toMQTTClimateEntity("danfoss_icon_thermostat_room_1", "danfoss/icon/%d/state", "danfoss/icon/%d/set", master);
        var json = JsonParser.parseString(Json.toJsonString(entity)).getAsJsonObject();
        assertFalse(json.has("temperature_high_state_topic"));
        assertFalse(json.has("temperature_low_state_topic"));
        assertEquals("danfoss_icon_thermostat_room_1", entity.unique_id());
        assertEquals("12345", entity.device().get("identifiers"));
        assertEquals("danfoss/icon/1/set", entity.temperature_command_topic());
        assertEquals("{{ value_json.attributes.temperature_target }}", entity.temperature_state_template());
        assertEquals("{{ value_json.attributes.hvac_mode }}", entity.mode_state_template());
        assertEquals("{{ value_json.attributes.hvac_action }}", entity.action_template());
        assertEquals(java.util.List.of("heat"), entity.modes());
        assertFalse(json.has("mode_command_topic"));
        assertEquals(java.util.List.of("home", "away", "sleep"), entity.preset_modes());
        var target = switch (preset) { case HOME -> "23.5"; case AWAY -> "15.0"; case SLEEP -> "19.0"; default -> throw new AssertionError(); };
        assertEquals(target, room.toState().attributes().get("temperature_target"));
        var rendered = entity.temperature_command_template().replace("{{ value }}", "22.5");
        assertEquals(22.5, JsonParser.parseString(rendered).getAsJsonObject().get("temperature_target").getAsDouble());
    }

    @ParameterizedTest
    @EnumSource(HeatingState.class)
    void reports_activity_separately_from_hvac_mode(HeatingState activity) {
        var room = new IconRoom("Room", 1, 22.3, 23.5, 15.0, 19.0,
                35.0, 5.0, (short) 99, activity, RoomMode.HOME);
        var attributes = room.toState().attributes();
        assertEquals(activity.name().toLowerCase(), attributes.get("mode")); // Legacy sensor automations.
        assertEquals(activity == HeatingState.COOL ? "cool" : "heat", attributes.get("hvac_mode"));
        assertEquals(switch (activity) { case OFF -> "idle"; case HEAT -> "heating"; case COOL -> "cooling"; }, attributes.get("hvac_action"));
        assertEquals("23.5", attributes.get("temperature_home"));
        assertEquals("15.0", attributes.get("temperature_away"));
        assertEquals("19.0", attributes.get("temperature_sleep"));
    }

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
