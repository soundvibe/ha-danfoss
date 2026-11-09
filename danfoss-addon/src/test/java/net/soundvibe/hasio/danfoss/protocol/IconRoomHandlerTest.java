package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.data.RoomMode;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import net.soundvibe.hasio.danfoss.protocol.config.Icon;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IconRoomHandlerTest {

    private static class CapturingConnector extends SDGPeerConnector {
        final List<Dominion.Packet> sent = new ArrayList<>();

        CapturingConnector() {
            super(null, new byte[0], null);
        }

        @Override
        public void SendPacket(Dominion.Packet pkt) {
            sent.add(pkt);
        }
    }

    @Test
    void should_send_room_mode_packet_and_update_state_when_room_mode_is_set() {
        var cases = new Object[][]{
                {RoomMode.HOME, Icon.RoomMode.AtHome},
                {RoomMode.AWAY, Icon.RoomMode.Away},
                {RoomMode.SLEEP, Icon.RoomMode.Asleep},
                {RoomMode.FATAL, Icon.RoomMode.Fatal},
        };
        for (var testCase : cases) {
            var roomMode = (RoomMode) testCase[0];
            var expectedByte = (byte) testCase[1];
            var connector = new CapturingConnector();
            var sut = new IconRoomHandler(connector, 5);

            sut.setRoomMode(roomMode);

            assertEquals(1, connector.sent.size());
            var packet = connector.sent.getFirst();
            assertEquals(Icon.MsgClass.ROOM_FIRST + 5, packet.getMsgClass());
            assertEquals(Icon.MsgCode.ROOM_ROOMMODE, packet.getMsgCode());
            assertEquals(expectedByte, packet.getByte());
            assertEquals(roomMode, sut.toIconRoom().roomMode());
        }
    }

    @Test
    void should_parse_preset_payloads() {
        assertEquals(Optional.of(RoomMode.HOME), IconRoomHandler.parsePreset("home"));
        assertEquals(Optional.of(RoomMode.AWAY), IconRoomHandler.parsePreset("away"));
        assertEquals(Optional.of(RoomMode.SLEEP), IconRoomHandler.parsePreset("sleep"));
    }

    @Test
    void should_tolerate_quoted_and_mixed_case_preset_payloads() {
        assertEquals(Optional.of(RoomMode.HOME), IconRoomHandler.parsePreset("\"home\""));
        assertEquals(Optional.of(RoomMode.AWAY), IconRoomHandler.parsePreset(" Away "));
        assertEquals(Optional.of(RoomMode.SLEEP), IconRoomHandler.parsePreset("SLEEP\n"));
    }

    @Test
    void should_reject_unknown_preset_payloads() {
        assertEquals(Optional.empty(), IconRoomHandler.parsePreset("party"));
        assertEquals(Optional.empty(), IconRoomHandler.parsePreset(""));
        assertEquals(Optional.empty(), IconRoomHandler.parsePreset(null));
    }
}
