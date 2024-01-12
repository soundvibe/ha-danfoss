package net.soundvibe.hasio.danfoss;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import net.soundvibe.hasio.HomeAssistantClient;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.soundvibe.hasio.Application.TOKEN_FILE;

public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);

    private static final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AppConfig appConfig;

    public Bootstrapper(AppConfig appConfig) {
        this.appConfig = appConfig;
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
            var homeAssistantClient = new HomeAssistantClient(
                    "http://supervisor/core/api",
                    token);
            logger.info("scheduling HA state updater");

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    for (IconRoom room : masterHandler.listRooms()) {
                        homeAssistantClient.upsertRoomThermostat(room);
                    }
                    logger.info("sensors updated successfully");
                } catch (Exception e) {
                    logger.error("sensor update error", e);
                }
            }, 1, 1, TimeUnit.MINUTES);
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
    }

    private String resolveToken() {
        var token = System.getenv("SUPERVISOR_TOKEN");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        try {
            new ProcessBuilder("/bin/bash", "token.sh").start();
        } catch (IOException e) {
            logger.warn("failed to execute token.sh", e);
        }

        if (Files.exists(TOKEN_FILE)) {
            try {
                token = Files.readString(TOKEN_FILE);
                if (token != null && !token.isEmpty()) {
                    return token.trim();
                }
            } catch (IOException e) {
                logger.error("failed to read token file", e);
            }
        }

        return "";
    }
}
