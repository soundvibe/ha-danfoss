package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.data.RoomMode;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import net.soundvibe.hasio.ha.RoomCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgClass.ROOM_FIRST;
import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgCode.*;
import static org.junit.jupiter.api.Assertions.*;

public class IconRoomHandlerTest {
    public static IconRoomHandler readyRoom(int number, List<Dominion.Packet> writes) {
        var room = new IconRoomHandler(writes::add, number);
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOMNAME, "Room " + number));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_ROOMTEMPERATURE, 21.0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_SETPOINTMINIMUM, 5.0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_SETPOINTMAXIMUM, 35.0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_SETPOINTATHOME, 23.5));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_SETPOINTAWAY, 15.0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_SETPOINTASLEEP, 19.0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_ROOMMODE, (byte) 0));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + number, ROOM_HEATINGCOOLINGSTATE, (byte) 0));
        return room;
    }

    @ParameterizedTest
    @EnumSource(value = RoomMode.class, names = {"HOME", "AWAY", "SLEEP"})
    void mqtt_writes_active_preset_and_only_reports_controller_feedback(RoomMode preset) {
        var writes = new ArrayList<Dominion.Packet>();
        var room = readyRoom(1, writes);
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + 1, ROOM_ROOMMODE, (byte) preset.ordinal()));
        var previous = room.toIconRoom().toState().attributes().get("temperature_target");
        RoomCommand.parse("danfoss/icon/1/set", "{\"temperature_target\":22.5,\"room_number\":1}").execute(room);
        int code = switch (preset) {
            case HOME -> ROOM_SETPOINTATHOME;
            case AWAY -> ROOM_SETPOINTAWAY;
            case SLEEP -> ROOM_SETPOINTASLEEP;
            default -> throw new AssertionError();
        };
        assertEquals(2, writes.size());
        assertEquals(code, writes.getFirst().getMsgCode());
        assertEquals(22.5, writes.getFirst().getDecimal());
        assertEquals(0, writes.getFirst().getBuffer()[0]); // Write, 22.5 encoded as 2250.
        assertEquals(1, writes.getLast().getBuffer()[0]); // Request readback.
        assertEquals(previous, room.toIconRoom().toState().attributes().get("temperature_target"));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + 1, code, 22.5));
        assertEquals("22.5", room.toIconRoom().toState().attributes().get("temperature_target"));
        // A physical thermostat change flows back through the same controller packet path.
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + 1, code, 24.0));
        assertEquals("24.0", room.toIconRoom().toState().attributes().get("temperature_target"));
    }

    @Test
    void each_room_is_addressed_independently() {
        for (int number = 0; number < 10; number++) {
            var writes = new ArrayList<Dominion.Packet>();
            var room = readyRoom(number, writes);
            RoomCommand.parse("danfoss/icon/" + number + "/set", "{\"temperature_target\":22.5}").execute(room);
            assertEquals(ROOM_FIRST + number, writes.getFirst().getMsgClass());
        }
    }

    @ParameterizedTest
    @EnumSource(value = RoomMode.class, names = {"HOME", "AWAY", "SLEEP"})
    void raw_presets_write_room_mode_without_changing_schedule_control(RoomMode preset) {
        var writes = new ArrayList<Dominion.Packet>();
        var room = readyRoom(1, writes);
        RoomCommand.parse("danfoss/icon/1/set", preset.name().toLowerCase()).execute(room);
        assertEquals(ROOM_ROOMMODE, writes.getFirst().getMsgCode());
        assertEquals(preset.ordinal(), writes.getFirst().getByte());
        assertEquals(2, writes.size());
        assertEquals(ROOM_ROOMMODE, writes.getLast().getMsgCode());
        assertTrue(writes.stream().noneMatch(p -> p.getMsgCode() == ROOM_ROOMCONTROL));
        assertEquals(RoomMode.HOME, room.toIconRoom().roomMode());
        room.handlePacket(writes.getFirst());
        assertEquals(preset, room.toIconRoom().roomMode());
    }

    @Test
    void rejects_invalid_commands_without_sending_packets() {
        var writes = new ArrayList<Dominion.Packet>();
        var room = readyRoom(1, writes);
        assertThrows(IllegalArgumentException.class, () -> room.setHomeTemperature(36));
        assertThrows(IllegalArgumentException.class, () -> room.setAwayTemperature(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> room.setSleepTemperature(4));
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + 1, ROOM_ROOMMODE, (byte) 3));
        assertThrows(IllegalStateException.class, () -> room.setTargetTemperature(22.5));
        assertTrue(writes.isEmpty());
    }

    @Test
    void startup_does_not_publish_partially_initialized_rooms() {
        var room = new IconRoomHandler(packet -> {}, 1);
        assertFalse(room.toIconRoom().isReady());
        room.handlePacket(new Dominion.Packet(ROOM_FIRST + 1, ROOMNAME, "Room"));
        assertFalse(room.toIconRoom().isReady());
        assertTrue(readyRoom(1, new ArrayList<>()).toIconRoom().isReady());
    }
}
