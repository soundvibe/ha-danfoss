package net.soundvibe.hasio.ha;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.danfoss.data.IconMaster;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandler;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** One subscription per connection, re-created after every clean-session reconnect. */
public final class RoomMqttClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RoomMqttClient.class);
    public static final String STATE_TOPIC_FMT = "danfoss/icon/%d/state";
    public static final String SET_TOPIC_FMT = "danfoss/icon/%d/set";
    private final MqttClient client;
    private volatile boolean subscribed;
    private final Map<Integer, IconRoom> lastPublishedRooms = new HashMap<>();

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
        publishRooms(master, () -> rooms);
    }

    public synchronized void publishRooms(IconMaster master, Supplier<List<IconRoom>> rooms) throws MqttException {
        ensureSubscribed();
        // Take snapshots inside the publication lock: a periodic refresh must not send
        // an older snapshot after the fast updater has published newer feedback.
        for (var room : rooms.get()) {
            if (!room.isReady()) continue;
            var id = STR."danfoss_icon_thermostat_room_\{room.number()}";
            client.publish(STR."homeassistant/climate/\{id}/config",
                    Json.toJsonBytes(room.toMQTTClimateEntity(id, STATE_TOPIC_FMT, SET_TOPIC_FMT, master)), 1, true);
            if (room.hasBattery()) {
                var batteryId = STR."\{id}_battery";
                client.publish(STR."homeassistant/sensor/\{batteryId}/config",
                        Json.toJsonBytes(room.toMQTTBatterySensorEntity(batteryId, STATE_TOPIC_FMT, master)), 1, true);
            }
            publishState(room);
        }
    }

    public synchronized void publishChangedRoomStates(Supplier<List<IconRoom>> rooms) throws MqttException {
        if (!client.isConnected()) return;
        for (var room : rooms.get()) {
            if (room.isReady() && !room.equals(lastPublishedRooms.get(room.number()))) {
                publishState(room);
            }
        }
    }

    private void publishState(IconRoom room) throws MqttException {
        // Only confirmed controller snapshots; never retain an old reading as fresh.
        client.publish(String.format(STATE_TOPIC_FMT, room.number()), Json.toJsonBytes(room.toState()), 0, false);
        lastPublishedRooms.put(room.number(), room);
    }

    @Override
    public synchronized void close() {
        try {
            if (client.isConnected()) client.disconnect();
            client.close(true);
        } catch (MqttException e) {
            logger.warn("Unable to close MQTT client", e);
        }
    }
}
