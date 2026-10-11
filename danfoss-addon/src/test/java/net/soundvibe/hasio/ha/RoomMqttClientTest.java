package net.soundvibe.hasio.ha;

import com.google.gson.JsonParser;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import net.soundvibe.hasio.danfoss.data.IconMaster;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandler;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandlerTest;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RoomMqttClientTest {
    private Server broker;
    private final Properties config = new Properties();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<MqttClient> clients = new ArrayList<>();
    private final List<RoomMqttClient> bridges = new ArrayList<>();
    private final IconMaster master = new IconMaster("House", 21, 10, "1", "2", "12345", 1, 0, null);
    private final Map<Integer, IconRoomHandler> rooms = new HashMap<>();
    private final List<Dominion.Packet> writes = Collections.synchronizedList(new ArrayList<>());
    private final BlockingQueue<Boolean> ready = new LinkedBlockingQueue<>();

    @BeforeEach
    void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        config.setProperty("host", "127.0.0.1");
        config.setProperty("port", String.valueOf(port));
        config.setProperty("allow_anonymous", "true");
        config.setProperty("persistence_enabled", "false");
        config.setProperty("telemetry_enabled", "false");
        config.setProperty("netty.boss_group_threads", "1");
        config.setProperty("netty.worker_group_threads", "2");
        startBroker();
        for (int number = 0; number < 10; number++) rooms.put(number, IconRoomHandlerTest.readyRoom(number, writes));
    }

    void startBroker() throws Exception {
        broker = new Server();
        broker.startServer(new MemoryConfig(config));
    }

    MqttClient client() throws Exception {
        var client = new MqttClient("tcp://127.0.0.1:" + config.getProperty("port"), UUID.randomUUID().toString(), new MemoryPersistence());
        clients.add(client);
        return client;
    }

    MqttConnectOptions options() {
        var options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(2);
        return options;
    }

    RoomMqttClient bridge() throws Exception {
        var client = client();
        var ref = new java.util.concurrent.atomic.AtomicReference<RoomMqttClient>();
        var bridge = new RoomMqttClient(client, n -> Optional.ofNullable(rooms.get(n)), executor, () -> {
            try {
                ref.get().publishRooms(master, rooms.values().stream().map(IconRoomHandler::toIconRoom).toList());
                ready.add(true);
            } catch (MqttException e) { throw new RuntimeException(e); }
        });
        ref.set(bridge);
        bridges.add(bridge);
        client.connect(options());
        assertNotNull(ready.poll(10, TimeUnit.SECONDS), "Bridge must subscribe and publish on connection");
        return bridge;
    }

    @AfterEach
    void stop() throws Exception {
        for (var bridge : bridges) bridge.close();
        for (var client : clients) {
            try { if (client.isConnected()) client.disconnect(); client.close(true); }
            catch (MqttException ignored) {}
        }
        executor.shutdownNow();
        if (broker != null) broker.stopServer();
    }

    @Test
    void retained_discovery_survives_addon_restart_and_preserves_all_ten_ids() throws Exception {
        var first = bridge();
        first.close();
        bridges.remove(first);
        bridge(); // A new client and clean session, as on add-on restart.
        var configs = new LinkedBlockingQueue<Map.Entry<String, MqttMessage>>();
        var lateHomeAssistant = client();
        lateHomeAssistant.connect(options());
        // Moquette 0.17 drops the RETAIN bit for QoS 1 delivery; HA's default receive QoS is 0.
        lateHomeAssistant.subscribe("homeassistant/climate/+/config", 0, (topic, message) -> configs.add(Map.entry(topic, message)));
        var ids = new HashSet<String>();
        for (int i = 0; i < 10; i++) {
            var config = configs.poll(5, TimeUnit.SECONDS);
            assertNotNull(config, "All rooms must be discoverable by a restarted HA");
            assertTrue(config.getValue().isRetained());
            var json = JsonParser.parseString(config.getValue().toString()).getAsJsonObject();
            var id = json.get("unique_id").getAsString();
            assertTrue(ids.add(id), "No duplicate identity");
            assertEquals("homeassistant/climate/" + id + "/config", config.getKey());
            assertFalse(json.has("temperature_high_state_topic"));
            assertFalse(json.has("temperature_low_state_topic"));
        }
        for (int i = 0; i < 10; i++) assertTrue(ids.contains("danfoss_icon_thermostat_room_" + i));
        lateHomeAssistant.publish("homeassistant/status", "online".getBytes(), 0, false);
        assertNotNull(ready.poll(5, TimeUnit.SECONDS), "HA birth message must refresh live room state");
    }

    @Test
    void commands_are_resubscribed_after_actual_broker_restart() throws Exception {
        bridge();
        var sender = client();
        sender.connect(options());
        sender.publish("danfoss/icon/1/set", "{\"temperature_target\":22.5,\"room_number\":1}".getBytes(), 1, false);
        await(() -> writes.size() == 2);
        assertEquals(22.5, writes.getFirst().getDecimal());
        writes.clear();
        broker.stopServer();
        broker = null;
        await(() -> !sender.isConnected());
        startBroker();
        assertNotNull(ready.poll(15, TimeUnit.SECONDS), "Automatic reconnect must restore subscriptions");
        await(sender::isConnected);
        sender.publish("danfoss/icon/1/set", "{\"temperature_target\":24.0}".getBytes(), 1, false);
        await(() -> writes.size() == 2);
        assertEquals(24.0, writes.getFirst().getDecimal());
    }

    @Test
    void ignores_retained_commands_and_accepts_presets_then_new_temperature_commands() throws Exception {
        var sender = client();
        sender.connect(options());
        sender.publish("danfoss/icon/1/set", "{\"temperature_target\":30.0}".getBytes(), 1, true);
        bridge();
        sender.publish("danfoss/icon/1/set", "away".getBytes(), 1, false);
        await(() -> writes.size() >= 2);
        assertEquals(2, writes.size(), "Old retained temperature must not be replayed");
        assertEquals(4106, writes.getFirst().getMsgCode());
        rooms.get(1).handlePacket(writes.getFirst()); // Controller confirms the active preset.
        writes.clear();
        sender.publish("danfoss/icon/1/set", "{\"temperature_target\":20.5}".getBytes(), 1, false);
        await(() -> writes.size() == 2);
        assertEquals(1290, writes.getFirst().getMsgCode()); // Away, not Home.
        assertEquals(20.5, writes.getFirst().getDecimal());
    }

    @Test
    void fast_updates_publish_only_changed_controller_feedback_without_extra_discovery() throws Exception {
        var bridge = bridge();
        var state = new LinkedBlockingQueue<MqttMessage>();
        var configs = new LinkedBlockingQueue<MqttMessage>();
        var observer = client();
        observer.connect(options());
        observer.subscribe("danfoss/icon/1/state", 0, (topic, message) -> state.add(message));
        observer.subscribe("homeassistant/climate/+/config", 0, (topic, message) -> configs.add(message));
        for (int i = 0; i < 10; i++) assertNotNull(configs.poll(5, TimeUnit.SECONDS));
        java.util.function.Supplier<List<net.soundvibe.hasio.danfoss.data.IconRoom>> snapshot =
                () -> rooms.values().stream().map(IconRoomHandler::toIconRoom).toList();

        bridge.publishChangedRoomStates(snapshot);
        assertNull(state.poll(200, TimeUnit.MILLISECONDS), "Unchanged state must not be repeatedly sent");
        observer.publish("danfoss/icon/1/set", "{\"temperature_target\":22.5}".getBytes(), 1, false);
        await(() -> writes.size() == 2);
        bridge.publishChangedRoomStates(snapshot);
        assertNull(state.poll(200, TimeUnit.MILLISECONDS), "A command alone is not controller feedback");

        rooms.get(1).handlePacket(new Dominion.Packet(net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgClass.ROOM_FIRST + 1,
                net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgCode.ROOM_SETPOINTATHOME, 22.5));
        bridge.publishChangedRoomStates(snapshot);
        var message = state.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "Confirmed target must be published without waiting for periodic refresh");
        assertFalse(message.isRetained());
        assertEquals("22.5", JsonParser.parseString(message.toString()).getAsJsonObject()
                .getAsJsonObject("attributes").get("temperature_target").getAsString());
        bridge.publishChangedRoomStates(snapshot);
        assertNull(state.poll(200, TimeUnit.MILLISECONDS));
        assertNull(configs.poll(200, TimeUnit.MILLISECONDS), "Fast feedback must not resend discovery");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for MQTT result");
    }
}
