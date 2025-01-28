package net.soundvibe.hasio;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.danfoss.protocol.IconRoomHandler;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import net.soundvibe.hasio.ha.HomeAssistantClient;
import net.soundvibe.hasio.ha.model.MQTTSetState;
import net.soundvibe.hasio.model.Command;
import net.soundvibe.hasio.model.Options;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.function.Predicate.not;
import static net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConstants.ICON_MAX_ROOMS;

public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8, Thread.ofVirtual().factory());
    private final Options options;

    private final Map<String, IMqttToken> subscribers = new ConcurrentHashMap<>(ICON_MAX_ROOMS * 2);

    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final Javalin app;
    private final AtomicReference<IconMasterHandler> masterHandler = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> scheduleHAUpdates = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> scheduleMQTTUpdates = new AtomicReference<>();

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

        var masterHandler = new IconMasterHandler(appConfig.privateKey(), executorService);
        masterHandler.scanRooms(appConfig.peerId());
        logger.info("rooms scanned: {}", appConfig.peerId());
        this.masterHandler.set(masterHandler);

        var token = resolveToken();
        logger.info("SUPERVISOR_TOKEN: {}", token);
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
            } catch (Throwable e) {
                ctx.status(500)
                        .result(STR."""
                        "status": "error", "error": "\{ e.getMessage() }"
                        """)
                        .contentType("application/json");
            }
        });
        opened.set(true);
    }

    private void executeCommand(Command cmd) {
        logger.info("executing cmd: {}", cmd.command());

        var maybeRoom = this.masterHandler.get().roomHandlerByNumber(cmd.roomNumber());
        if (maybeRoom.isEmpty()) {
            logger.info("room not found: {}", cmd.roomNumber());
            return;
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
                logger.warn("unknown command: {}", cmd.command());
        }
    }

    private ScheduledFuture<?> scheduleHomeAssistantUpdates(String token, Options options) {
        var masterHandler = this.masterHandler.get();
        var homeAssistantClient = new HomeAssistantClient(token);
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                for (var room : masterHandler.listRooms()) {
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
        String clientID = UUID.randomUUID().toString();
        try {
            var mqttClient = new MqttClient(STR."tcp://\{options.mqttHost()}:\{options.mqttPort()}", clientID);
            var mqttConnOptions = new MqttConnectOptions();
            mqttConnOptions.setAutomaticReconnect(true);
            mqttConnOptions.setCleanSession(true);
            mqttConnOptions.setConnectionTimeout(10);
            mqttConnOptions.setKeepAliveInterval(options.mqttKeepAlive());
            mqttConnOptions.setUserName(options.mqttUsername());
            mqttConnOptions.setPassword(options.mqttPassword().toCharArray());
            mqttClient.connect(mqttConnOptions);
            Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
                try {
                    mqttClient.close(true);
                } catch (MqttException e) {
                    // nop
                }
            }));
            logger.info("MQTT connection established successfully");
            return scheduler.scheduleAtFixedRate(() -> {
                var masterHandler = this.masterHandler.get();
                try {
                    var iconMaster = masterHandler.iconMaster();
                    for (var room : masterHandler.listRooms()) {
                        // first publish climate device
                        var thermostatID = STR."danfoss_icon_thermostat_room_\{room.number()}";
                        var entityTopic = STR."homeassistant/climate/\{thermostatID}/config";
                        var climateEntity = room.toMQTTClimateEntity(thermostatID, STATE_TOPIC_FMT, SET_TOPIC_FMT, iconMaster);
                        mqttClient.publish(entityTopic, Json.toJsonBytes(climateEntity), 0, false);

                        // now publish update to state topic
                        var stateTopic = String.format(STATE_TOPIC_FMT, room.number());
                        var state = room.toState();
                        mqttClient.publish(stateTopic, Json.toJsonBytes(state), 0, false);

                        // and finally subscribe to set topic
                        var setTopic = String.format(SET_TOPIC_FMT, room.number());
                        var mqttToken = subscribeToTopic(setTopic, mqttClient);
                        if (!mqttToken.isComplete()) {
                            //force resubscribe
                            mqttClient.unsubscribe(setTopic);
                            subscribers.remove(setTopic);
                            logger.info("MQTT subscriber removed = {}", setTopic);
                        }
                    }
                    logger.info("MQTT sensors updated successfully");
                } catch (Exception e) {
                    logger.error("MQTT sensor update error", e);
                }
            }, 1, options.haUpdatePeriodInSeconds(), TimeUnit.SECONDS);
        } catch (MqttException e) {
            logger.error("unable to connect to MQTT broker", e);
            throw new RuntimeException(e);
        }
    }

    private IMqttToken subscribeToTopic(String setTopic, MqttClient mqttClient) {
        return subscribers.compute(setTopic, (key, listener) -> {
            if (listener != null) {
                return listener;
            }

            IMqttMessageListener newListener = (_, message) -> {
                try {
                    var setState = Json.fromString(message.toString(), MQTTSetState.class);
                    // get room preset
                    masterHandler.get().roomHandlerByNumber(setState.room_number())
                            .map(IconRoomHandler::toIconRoom)
                            .map(room -> switch (room.roomMode()) {
                                case HOME -> "setHomeTemperature";
                                case AWAY -> "setAwayTemperature";
                                case SLEEP -> "setSleepTemperature";
                                default -> "";
                            })
                            .filter(not(String::isEmpty))
                            .map(cmdName -> new Command(cmdName, setState.room_number(), setState.temperature_target()))
                            .ifPresent(this::executeCommand);
                } catch (Throwable e) {
                    logger.warn("got error on topic listener", e);
                }
            };
            try {
                var token = mqttClient.subscribeWithResponse(key, newListener);
                if (token.getException() != null) {
                    throw token.getException();
                }
                logger.info("subscribed to MQTT topic {} successfully", key);
                return token;
            } catch (MqttException e) {
                logger.error("unable to subscribe to MQTT topic", e);
                throw new RuntimeException(e);
            }
        });
    }

    private static final String STATE_TOPIC_FMT = "danfoss/icon/%d/state";
    private static final String SET_TOPIC_FMT = "danfoss/icon/%d/set";
}
