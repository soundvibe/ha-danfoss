package net.soundvibe.hasio;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import net.soundvibe.hasio.ha.HomeAssistantClient;
import net.soundvibe.hasio.ha.RoomMqttClient;
import net.soundvibe.hasio.model.Command;
import net.soundvibe.hasio.model.Options;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8, Thread.ofVirtual().factory());
    private final Options options;

    private final AtomicReference<RoomMqttClient> roomMqttClient = new AtomicReference<>();

    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final Javalin app;
    private final AtomicReference<IconMasterHandler> masterHandler = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> scheduleHAUpdates = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> scheduleMQTTUpdates = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> scheduleChangedMQTTUpdates = new AtomicReference<>();

    public Bootstrapper(Javalin app, Options options) {
        this.app = app;
        this.options = options;
    }

    public void load(AppConfig appConfig) {
        if (this.scheduleHAUpdates.get() != null) {
            this.scheduleHAUpdates.get().cancel(true);
        }
        if (scheduleMQTTUpdates.get() != null) {
            scheduleMQTTUpdates.get().cancel(true);
        }

        var changedUpdates = scheduleChangedMQTTUpdates.getAndSet(null);
        if (changedUpdates != null) changedUpdates.cancel(true);

        var previousMqttClient = roomMqttClient.getAndSet(null);
        if (previousMqttClient != null) previousMqttClient.close();

        var masterHandler = new IconMasterHandler(appConfig.privateKey(), executorService);
        masterHandler.scanRooms(appConfig.peerId());
        logger.info("rooms scanned: {}", appConfig.peerId());
        this.masterHandler.set(masterHandler);

        var token = resolveToken();
        if (token.isEmpty()) {
            logger.warn("authorization token not found");
        } else {
            logger.info("scheduling HA state updater");
            this.scheduleHAUpdates.set(scheduleHomeAssistantUpdates(token, options));
        }

        if (options.mqttEnabled()) {
            scheduleMQTTUpdates.set(scheduleMQTTUpdates(options));
        }

        if (opened.get()) {
            return;
        }

        app.get("/rooms", ctx -> {
            var rooms = this.masterHandler.get().listRooms();
            ctx.json(rooms);
        });
        app.get("/rooms/{roomName}", ctx -> {
            var roomName = ctx.queryParam("roomName");
            this.masterHandler.get().listRooms().stream()
                    .filter(iconRoom -> iconRoom.name().equals(roomName))
                    .findAny()
                    .ifPresentOrElse(ctx::json, () -> ctx.status(HttpStatus.NOT_FOUND));
        });
        app.post("/command", ctx -> {
            try {
                var command = Json.fromString(ctx.body(), Command.class);
                executeCommand(command);
                ctx.status(200)
                        .result("""
            { "status": "OK" }""")
                        .contentType("application/json");
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(java.util.Map.of("status", "error", "error", e.getMessage()));
            } catch (Exception e) {
                ctx.status(500).json(java.util.Map.of("status", "error", "error", e.toString()));
            }
        });
        opened.set(true);
    }

    private void executeCommand(Command cmd) {
        logger.info("executing cmd: {}", cmd.command());

        var maybeRoom = this.masterHandler.get().roomHandlerByNumber(cmd.roomNumber());
        if (maybeRoom.isEmpty()) {
            throw new IllegalArgumentException("Room not found: " + cmd.roomNumber());
        }
        var roomHandler =  maybeRoom.get();

        switch (cmd.command()) {
            case "setHomeTemperature": {
                roomHandler.setHomeTemperature(cmd.value());
                break;
            }
            case "setAwayTemperature": {
                roomHandler.setAwayTemperature(cmd.value());
                break;
            }
            case "setSleepTemperature": {
                roomHandler.setSleepTemperature(cmd.value());
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown command: " + cmd.command());
        }
    }

    private ScheduledFuture<?> scheduleHomeAssistantUpdates(String token, Options options) {
        var masterHandler = this.masterHandler.get();
        var homeAssistantClient = new HomeAssistantClient(token);
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                for (var room : masterHandler.listRooms()) {
                    if (!room.isReady()) continue;
                    var sensorName = String.format(options.sensorNameFmt(), room.number());
                    var state = room.toState();
                    homeAssistantClient.upsertState(state, sensorName);
                }

                var iconMaster = masterHandler.iconMaster();
                homeAssistantClient.upsertState(iconMaster.toState(), "sensor.danfoss_master_controller_last_updated");
                logger.info("sensors updated successfully");
            } catch (Exception e) {
                logger.error("sensor update error", e);
            }
        }, 1, options.haUpdatePeriodInSeconds(), TimeUnit.SECONDS);
    }

    private String resolveToken() {
        var token = System.getenv("SUPERVISOR_TOKEN");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        return System.getProperty("SUPERVISOR_TOKEN", "");
    }

    private ScheduledFuture<?> scheduleMQTTUpdates(Options options) {
        try {
            var mqttClient = new MqttClient(STR."tcp://\{options.mqttHost()}:\{options.mqttPort()}", UUID.randomUUID().toString());
            var roomClient = new RoomMqttClient(mqttClient,
                    number -> this.masterHandler.get().roomHandlerByNumber(number), scheduler, this::publishMQTTUpdates);
            roomMqttClient.set(roomClient);
            var mqttConnOptions = new MqttConnectOptions();
            mqttConnOptions.setAutomaticReconnect(true);
            mqttConnOptions.setCleanSession(true);
            mqttConnOptions.setConnectionTimeout(10);
            mqttConnOptions.setKeepAliveInterval(options.mqttKeepAlive());
            mqttConnOptions.setUserName(options.mqttUsername());
            mqttConnOptions.setPassword(options.mqttPassword().toCharArray());
            mqttClient.connect(mqttConnOptions);
            Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(roomClient::close));
            logger.info("MQTT connection established successfully");
            // Compare local controller snapshots; this makes no extra Danfoss requests.
            scheduleChangedMQTTUpdates.set(scheduler.scheduleWithFixedDelay(this::publishChangedMQTTStates,
                    500, 500, TimeUnit.MILLISECONDS));
            return scheduler.scheduleAtFixedRate(this::publishMQTTUpdates,
                    1, options.haUpdatePeriodInSeconds(), TimeUnit.SECONDS);
        } catch (MqttException e) {
            logger.error("unable to connect to MQTT broker", e);
            throw new RuntimeException(e);
        }
    }

    private void publishMQTTUpdates() {
        var client = roomMqttClient.get();
        if (client == null) return;
        try {
            var master = masterHandler.get();
            client.publishRooms(master.iconMaster(), master::listRooms);
            logger.info("MQTT sensors updated successfully");
        } catch (Exception e) {
            logger.error("MQTT sensor update error", e);
        }
    }
    private void publishChangedMQTTStates() {
        var client = roomMqttClient.get();
        if (client == null) return;
        try {
            client.publishChangedRoomStates(masterHandler.get()::listRooms);
        } catch (Exception e) {
            logger.warn("Unable to publish changed MQTT room states", e);
        }
    }

}
