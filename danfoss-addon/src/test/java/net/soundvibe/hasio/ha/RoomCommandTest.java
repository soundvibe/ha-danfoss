package net.soundvibe.hasio.ha;

import net.soundvibe.hasio.danfoss.data.RoomMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomCommandTest {
    @Test
    void accepts_legacy_json_including_room_zero_and_numeric_strings() {
        var command = RoomCommand.parse("danfoss/icon/0/set",
                "{\"temperature_target\":\"22.5\",\"room_number\":\"0\"}");
        assertEquals(0, command.roomNumber());
        assertEquals(22.5, command.temperature());
        assertEquals(RoomMode.AWAY, RoomCommand.parse("danfoss/icon/1/set", "away").preset());
        assertEquals(RoomMode.SLEEP, RoomCommand.parse("danfoss/icon/1/set", "{\"preset\":\"sleep\"}").preset());
    }

    @Test
    void rejects_mismatched_rooms_missing_targets_and_unsupported_hvac_commands() {
        for (var payload : new String[]{"{}", "{\"room_number\":1}", "{\"temperature_target\":22.5,\"room_number\":2}",
                "{\"temperature_target\":22.5,\"room_number\":1.5}", "{\"temperature_target\":null}",
                "{\"temperature_target\":\"NaN\"}", "{\"hvac_mode\":\"off\"}", "off", "fatal",
                "{\"temperature_target\":22.5,\"preset\":\"away\"}"}) {
            assertThrows(RuntimeException.class, () -> RoomCommand.parse("danfoss/icon/1/set", payload), payload);
        }
    }
}
