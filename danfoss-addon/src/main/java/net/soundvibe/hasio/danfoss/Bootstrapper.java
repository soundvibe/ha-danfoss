package net.soundvibe.hasio.danfoss;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import net.soundvibe.hasio.HomeAssistantClient;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.danfoss.protocol.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Bootstrapper {

    private static final Logger logger = LoggerFactory.getLogger(Bootstrapper.class);
    private final AppConfig appConfig;

    public Bootstrapper(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public void bootstrap(Javalin app) {
        var executorService = Executors.newScheduledThreadPool(128, Thread.ofVirtual().factory());
        var masterHandler = new IconMasterHandler(appConfig.privateKey(), executorService);

        masterHandler.scanRooms(appConfig.peerId());

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

        var token = System.getenv("SUPERVISOR_TOKEN");
        if (token == null || token.isEmpty()) {
            logger.warn("authorization token not found");
        } else {
            var homeAssistantClient = new HomeAssistantClient(
                    "http://supervisor/core/api",
                    System.getProperty("SUPERVISOR_TOKEN", ""));
            executorService.scheduleAtFixedRate(() -> {
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
    }
}
