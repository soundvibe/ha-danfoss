package net.soundvibe.hasio.ha;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.danfoss.data.IconMaster;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandler;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;

/** One subscription per connection, re-created after every clean-session reconnect. */
public final class RoomMqttClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RoomMqttClient.class);
    public static final String STATE_TOPIC_FMT = "danfoss/icon/%d/state";
    public static final String SET_TOPIC_FMT = "danfoss/icon/%d/set";
    private final MqttClient client;
    private volatile boolean subscribed;

    public RoomMqttClient(MqttClient client, IntFunction<Optional<IconRoomHandler>> rooms,
                          Executor executor, Runnable publishUpdates) {
        this.client = client;
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                subscribed = false;
                // Paho's callback thread cannot block waiting for a SUBACK.
                executor.execute(() -> {
                    try {
                        ensureSubscribed();
                        publishUpdates.run();
                    } catch (Exception e) {
                        logger.warn("MQTT reconnect setup failed; periodic updater will retry", e);
                    }
                });
            }

            @Override
            public void connectionLost(Throwable cause) {
                subscribed = false;
                logger.warn("MQTT connection lost", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                if (message.isRetained()) return; // Never replay an old heating command on restart.
                if (topic.equals("homeassistant/status")) {
                    if (message.toString().equals("online")) executor.execute(publishUpdates);
                    return;
                }
                try {
                    var command = RoomCommand.parse(topic, message.toString());
                    var room = rooms.apply(command.roomNumber()).orElseThrow(
                            () -> new IllegalArgumentException("Room not found: " + command.roomNumber()));
                    command.execute(room);
                    logger.info("MQTT command sent to room {}; awaiting controller feedback", command.roomNumber());
                } catch (Exception e) {
                    logger.warn("Rejected command on {}: {}", topic, e.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });
    }

    public synchronized void ensureSubscribed() throws MqttException {
        if (!subscribed) {
            client.subscribe(new String[]{"danfoss/icon/+/set", "homeassistant/status"}, new int[]{0, 0});
            subscribed = true;
        }
    }

    public void publishRooms(IconMaster master, List<IconRoom> rooms) throws MqttException {
        ensureSubscribed();
        for (var room : rooms) {
            if (!room.isReady()) continue;
            var id = STR."danfoss_icon_thermostat_room_\{room.number()}";
            client.publish(STR."homeassistant/climate/\{id}/config",
                    Json.toJsonBytes(room.toMQTTClimateEntity(id, STATE_TOPIC_FMT, SET_TOPIC_FMT, master)), 1, true);
            if (room.hasBattery()) {
                var batteryId = STR."\{id}_battery";
                client.publish(STR."homeassistant/sensor/\{batteryId}/config",
                        Json.toJsonBytes(room.toMQTTBatterySensorEntity(batteryId, STATE_TOPIC_FMT, master)), 1, true);
            }
            // State remains live, so a retained old temperature cannot masquerade as a fresh reading.
            client.publish(String.format(STATE_TOPIC_FMT, room.number()), Json.toJsonBytes(room.toState()), 0, false);
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) client.disconnect();
            client.close(true);
        } catch (MqttException e) {
            logger.warn("Unable to close MQTT client", e);
        }
    }
}
