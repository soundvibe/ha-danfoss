package net.soundvibe.hasio.model;

import net.soundvibe.hasio.Json;

import java.nio.file.Files;
import java.nio.file.Path;

public record Options(
        int haUpdatePeriodInSeconds,
        String sensorNameFmt,
        int port,
        boolean mqttEnabled,
        String mqttHost,
        int mqttPort,
        int mqttKeepAlive,
        String mqttUsername,
        String mqttPassword,
        String logLevel

) {

    public static Options fromPath(Path path) {
        if (Files.exists(path)) {
            return Json.fromPath(path, Options.class);
        }

        // serve defaults
        return new Options(1, "sensor.danfoss_%d_temperature", 9199,
                false, "core-mosquitto", 1883, 60, "", "", "info");
    }

}
