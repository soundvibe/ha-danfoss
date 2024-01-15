package net.soundvibe.hasio;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import net.soundvibe.hasio.ha.HomeAssistantClient;
import net.soundvibe.hasio.model.Command;
import net.soundvibe.hasio.model.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.soundvibe.hasio.Application.ADDON_CONFIG_FILE;

public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AppConfig appConfig;

    public Bootstrapper(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public void bootstrap(Javalin app) {
        var options = Options.fromPath(ADDON_CONFIG_FILE);
        logger.info("parsed options: haUpdatePeriodInMinutes={}, sensorNameFmt={}",
                options.haUpdatePeriodInMinutes(), options.sensorNameFmt());

        var masterHandler = new IconMasterHandler(appConfig.privateKey(), executorService);
        masterHandler.scanRooms(appConfig.peerId());
        logger.info("rooms scanned: {}", appConfig.peerId());

        var token = resolveToken();
        logger.info("SUPERVISOR_TOKEN: {}", token);
        if (token.isEmpty()) {
            logger.warn("authorization token not found");
        } else {
            logger.info("scheduling HA state updater");
            updateHA(masterHandler, token, options);
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
                        .result(String.format("""
                        "status": "error", "error": "%s"))""", e.getMessage()))
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

    private static void updateHA(IconMasterHandler masterHandler, String token, Options options) {
        var homeAssistantClient = new HomeAssistantClient(token, options);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (IconRoom room : masterHandler.listRooms()) {
                    homeAssistantClient.upsertRoomThermostat(room);
                }
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
}
