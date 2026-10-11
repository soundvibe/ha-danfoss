package net.soundvibe.hasio.ha;

import com.google.gson.JsonParser;
import net.soundvibe.hasio.danfoss.data.RoomMode;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandler;

import java.util.Locale;
import java.util.regex.Pattern;

/** The topic identifies the room; an optional legacy room_number must agree. */
public record RoomCommand(int roomNumber, Double temperature, RoomMode preset) {
    private static final Pattern TOPIC = Pattern.compile("danfoss/icon/(\\d+)/set");

    public static RoomCommand parse(String topic, String payload) {
        var match = TOPIC.matcher(topic);
        if (!match.matches()) throw new IllegalArgumentException("Invalid room command topic");
        int roomNumber = Integer.parseInt(match.group(1));
        var text = payload.trim();
        // Preserve the raw preset payloads sent by the existing discovery configuration.
        if (!text.startsWith("{")) {
            return new RoomCommand(roomNumber, null, parsePreset(text));
        }
        var json = JsonParser.parseString(text).getAsJsonObject();
        if (json.has("room_number") && json.get("room_number").getAsDouble() != roomNumber) {
            throw new IllegalArgumentException("Payload room_number does not match topic");
        }
        if (json.has("temperature_target") && json.size() == (json.has("room_number") ? 2 : 1)) {
            double temperature = json.get("temperature_target").getAsDouble();
            if (!Double.isFinite(temperature)) throw new IllegalArgumentException("Invalid temperature");
            return new RoomCommand(roomNumber, temperature, null);
        }
        if (json.has("preset") && json.size() == (json.has("room_number") ? 2 : 1)) {
            return new RoomCommand(roomNumber, null, parsePreset(json.get("preset").getAsString()));
        }
        throw new IllegalArgumentException("Expected a temperature_target or preset command");
    }

    private static RoomMode parsePreset(String value) {
        var preset = RoomMode.valueOf(value.toUpperCase(Locale.ROOT));
        if (preset == RoomMode.FATAL) throw new IllegalArgumentException("Invalid preset");
        return preset;
    }

    public void execute(IconRoomHandler room) {
        if (temperature != null) room.setTargetTemperature(temperature);
        else room.setPreset(preset);
    }
}
