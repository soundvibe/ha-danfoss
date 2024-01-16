package net.soundvibe.hasio.danfoss.data;

import java.util.Map;

import static java.util.Map.entry;

public record IconRoom(String name, int number, double temperature,
                       double temperatureHome, double temperatureAway, double temperatureSleep, double temperatureHigh, double temperatureLow,
                       short batteryPercent, HeatingState mode, RoomMode roomMode) {

    public State toState() {
        return new State(String.valueOf(temperature), Map.ofEntries(
                entry("unit_of_measurement", "°C"),
                entry("friendly_name", String.format("%s temperature", name)),
                entry("device_class", "temperature"),
                entry("state_class", "measurement"),
                entry("battery_level", String.valueOf(batteryPercent)),
                entry("temperature_home", String.valueOf(temperatureHome)),
                entry("temperature_away", String.valueOf(temperatureAway)),
                entry( "temperature_sleep", String.valueOf(temperatureSleep)),
                entry( "temperature_high", String.valueOf(temperatureHigh)),
                entry( "temperature_low", String.valueOf(temperatureLow)),
                entry("room_number", String.valueOf(number)),
                entry("mode", mode.name().toLowerCase()),
                entry("preset", roomMode.name().toLowerCase())
        ));
    }
}
