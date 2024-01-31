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
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.function.Predicate.not;
import static net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConstants.ICON_MAX_ROOMS;

public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8, Thread.ofVirtual().factory());
    private final AppConfig appConfig;
    private final Options options;

    private final Map<String, IMqttMessageListener> subscribers = new ConcurrentHashMap<>(ICON_MAX_ROOMS * 2);

    public Bootstrapper(AppConfig appConfig, Options options) {
        this.appConfig = appConfig;
        this.options = options;
    }

    public void bootstrap(Javalin app) {
        var masterHandler = new IconMasterHandler(appConfig.privateKey(), executorService);
        masterHandler.scanRooms(appConfig.peerId());
        logger.info("rooms scanned: {}", appConfig.peerId());

        var token = resolveToken();
        logger.info("SUPERVISOR_TOKEN: {}", token);
        if (token.isEmpty()) {
            logger.warn("authorization token not found");
        } else {
            logger.info("scheduling HA state updater");
            scheduleHomeAssistantUpdates(masterHandler, token, options);
        }

        if (options.mqttEnabled()) {
            scheduleMQTTUpdates(masterHandler, options);
        }

        app.get("/rooms", ctx -> {
            var rooms = masterHandler.listRooms();
            ctx.json(rooms);
        });
        app.get("/rooms/{roomName}", ctx -> {
            var roomName = ctx.queryParam("roomName");
            masterHandler.listRooms().stream()
                    .filter(iconRoom -> iconRoom.name().equals(roomName))
                    .findAny()
                    .ifPresentOrElse(ctx::json, () -> ctx.status(HttpStatus.NOT_FOUND));
        });
        app.post("/command", ctx -> {
            try {
                var command = Json.fromString(ctx.body(), Command.class);
                executeCommand(masterHandler, command);
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
    }

    private void executeCommand(IconMasterHandler masterHandler, Command cmd) {
        logger.info("executing cmd: {}", cmd.command());

        var maybeRoom = masterHandler.roomHandlerByNumber(cmd.roomNumber());
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

    private static void scheduleHomeAssistantUpdates(IconMasterHandler masterHandler, String token, Options options) {
        var homeAssistantClient = new HomeAssistantClient(token);
        scheduler.scheduleAtFixedRate(() -> {
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
        }, 1, options.haUpdatePeriodInMinutes(), TimeUnit.MINUTES);
    }

    private String resolveToken() {
        var token = System.getenv("SUPERVISOR_TOKEN");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        return System.getProperty("SUPERVISOR_TOKEN", "");
    }

    private void scheduleMQTTUpdates(IconMasterHandler masterHandler, Options options) {
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
            scheduler.scheduleAtFixedRate(() -> {
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
                        subscribeToTopic(masterHandler, setTopic, mqttClient);
                    }

                    logger.info("MQTT sensors updated successfully");
                } catch (Exception e) {
                    logger.error("MQTT sensor update error", e);
                }
            }, 0, options.haUpdatePeriodInMinutes(), TimeUnit.MINUTES);
        } catch (MqttException e) {
            logger.error("unable to connect to MQTT broker", e);
            throw new RuntimeException(e);
        }
    }

    private void subscribeToTopic(IconMasterHandler masterHandler, String setTopic, MqttClient mqttClient) {
        subscribers.compute(setTopic, (key, listener) -> {
            if (listener != null) {
                return listener;
            }

            IMqttMessageListener newListener = (_, message) -> {
                var setState = Json.fromString(message.toString(), MQTTSetState.class);
                // get room preset
                masterHandler.roomHandlerByNumber(setState.room_number())
                        .map(IconRoomHandler::toIconRoom)
                        .map(room -> switch (room.roomMode()) {
                            case HOME -> "setHomeTemperature";
                            case AWAY -> "setAwayTemperature";
                            case SLEEP -> "setSleepTemperature";
                            default -> "";
                        })
                        .filter(not(String::isEmpty))
                        .map(cmdName -> new Command(cmdName, setState.room_number(), setState.temperature_target()))
                        .ifPresent(command -> executeCommand(masterHandler, command));
            };
            try {
                mqttClient.subscribe(key, newListener);
                logger.info("subscribed to MQTT topic {} successfully", key);
                return newListener;
            } catch (MqttException e) {
                logger.error("unable to subscribe to MQTT topic", e);
                throw new RuntimeException(e);
            }
        });
    }

    private static final String STATE_TOPIC_FMT = "danfoss/icon/%d/state";
    private static final String SET_TOPIC_FMT = "danfoss/icon/%d/set";
}
