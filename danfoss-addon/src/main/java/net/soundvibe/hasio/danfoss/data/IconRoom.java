package net.soundvibe.hasio.danfoss.data;

import java.util.Map;

import static net.soundvibe.hasio.Bootstrapper.XX_HASH;

public record IconRoom(String name, int number, double temperature, double temperatureHome, double temperatureAway, int batteryPercent) {

    public State toState() {
        return new State(String.valueOf(temperature), Map.of(
                "unit_of_measurement", "°C",
                "friendly_name", String.format("%s temperature", name),
                "device_class", "temperature",
                "state_class", "measurement",
                "battery_level", String.valueOf(batteryPercent),
                "temperature_home", String.valueOf(temperatureHome),
                "temperature_away", String.valueOf(temperatureAway),
                "unique_id", String.format("%d%d", XX_HASH.hashChars(name), number)
        ));
    }
}
