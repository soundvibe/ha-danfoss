package net.soundvibe.hasio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.data.State;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class HomeAssistantClient {

    private static final Logger logger = LoggerFactory.getLogger(HomeAssistantClient.class);

    private final String endpoint;
    private final String token;
    private final OkHttpClient httpClient;

    private final Gson gson = new GsonBuilder().create();

    public HomeAssistantClient(String endpoint, String token) {
        this.endpoint = endpoint;
        this.token = token;
        this.httpClient = new OkHttpClient.Builder().build();
    }

    public void upsertRoomThermostat(IconRoom room) {
        if (this.token.isEmpty()) {
            logger.info("no token, skipping");
            return;
        }

        var sensorName = String.format("sensor.danfoss_%d_temperature", room.number());

        var sensorState = new State(String.valueOf(room.temperature()), Map.of(
                "unit_of_measurement", "°C",
                "friendly_name", String.format("%s temperature", room.name()),
                "device_class", "temperature",
                "state_class", "measurement",
                "battery_level", String.valueOf(room.batteryPercent()),
                "temperature_home", String.valueOf(room.temperatureHome()),
                "temperature_away", String.valueOf(room.temperatureAway())
        ));
        var jsonString = this.gson.toJson(sensorState);

        try {
            var body = RequestBody.create(jsonString, MediaType.parse("application/json"));
            // temperature
            var request = new Request.Builder()
                    .url(String.format("%s/states/%s", this.endpoint, sensorName))
                    .header("Authorization", String.format("Bearer %s", this.token))
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            Call call = this.httpClient.newCall(request);
            try (var response = call.execute()) {
                if (response.code() == 200 || response.code() == 201) {
                    return;
                }

                logger.error("[{}]: failed to upsert sensor: {}", response.code(), response.message());
            }
        } catch (Exception e) {
            logger.error("unable to upsert Home Assistant states", e);
            throw new RuntimeException(e);
        }
    }
}
