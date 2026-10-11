package net.soundvibe.hasio.model;

import com.google.gson.JsonParser;
import net.soundvibe.hasio.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionsTest {
    @Test
    void diagnostic_string_redacts_password_without_changing_mqtt_configuration() {
        var options = new Options(60, "sensor.danfoss_%d_temperature", 9199,
                true, "core-mosquitto", 1883, 60, "mqtt-user", "secret-password", "info");
        assertFalse(options.toString().contains("secret-password"));
        assertTrue(options.toString().contains("mqttPassword=<redacted>"));
        assertEquals("secret-password", options.mqttPassword());
        var json = JsonParser.parseString(new String(Json.toJsonBytes(options))).getAsJsonObject();
        assertEquals("secret-password", json.get("mqttPassword").getAsString());
    }
}
