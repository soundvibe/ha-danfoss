package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.ha.model.MQTTClimateEntity;
import net.soundvibe.hasio.ha.model.State;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

public record IconRoom(String name, int number, double temperature,
                       double temperatureHome, double temperatureAway, double temperatureSleep, double temperatureHigh, double temperatureLow,
                       short batteryPercent, HeatingState mode, RoomMode roomMode) {

    public State toState() {
        var temperatureTarget = switch (roomMode) {
            case HOME, FATAL -> temperatureHome;
            case AWAY -> temperatureAway;
            case SLEEP -> temperatureSleep;
        };
        return new State(String.valueOf(temperature), Map.ofEntries(
                entry("unit_of_measurement", "°C"),
                entry("friendly_name", String.format("%s temperature", name)),
                entry("device_class", "temperature"),
                entry("state_class", "measurement"),
                entry("availability", "online"),
                entry("battery_level", String.valueOf(batteryPercent)),
                entry("temperature_home", String.valueOf(temperatureHome)),
                entry("temperature_away", String.valueOf(temperatureAway)),
                entry( "temperature_sleep", String.valueOf(temperatureSleep)),
                entry( "temperature_high", String.valueOf(temperatureHigh)),
                entry( "temperature_low", String.valueOf(temperatureLow)),
                entry( "temperature_target", String.valueOf(temperatureTarget)),
                entry("room_number", String.valueOf(number)),
                entry("mode", mode.name().toLowerCase()),
                entry("preset", roomMode.name().toLowerCase())
        ));
    }

    public MQTTClimateEntity toMQTTClimateEntity(String id, String stateTopicFmt, String setTopicFmt, IconMaster iconMaster) {
        var stateTopic = String.format(stateTopicFmt, number);
        var setTempTopic = String.format(setTopicFmt, number);
        return new MQTTClimateEntity(id, name, MODES, temperatureLow, temperatureHigh, 0.5,
                Map.of("name", iconMaster.houseName(), "model", "Icon", "manufacturer", "Danfoss",
                        "hw_version", iconMaster.hardwareRevision(), "sw_version", iconMaster.softwareRevision(),
                        "identifiers", iconMaster.serialNumber()),
                stateTopic, "{{ value_json.attributes.availability }}",
                stateTopic, "{{ value_json.state }}",
                stateTopic, "{{ value_json.attributes.mode }}",
                PRESET_MODES, setTempTopic,
                stateTopic, "{{ value_json.attributes.preset }}",
                setTempTopic, STR."""
                { 'temperature_target': {{ value }}, 'room_number': \{number} }""", // set target temperature
                stateTopic, "{{ value_json.attributes.temperature_target }}", //target temperature state
                stateTopic, "{{ value_json.attributes.temperature_high }}",
                stateTopic, "{{ value_json.attributes.temperature_low }}"
        );
    }

    private static final List<String> MODES = List.of("off", "heat");
    private static final List<String> PRESET_MODES = List.of("home", "away", "sleep");
}
