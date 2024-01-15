package net.soundvibe;

import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.danfoss.protocol.IconMasterHandler;
import net.soundvibe.hasio.model.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

public class StdInListener {

    private static final Logger logger = LoggerFactory.getLogger(StdInListener.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final IconMasterHandler masterHandler;

    public StdInListener(IconMasterHandler masterHandler) {
        this.masterHandler = masterHandler;
    }

    public void start() {
        logger.info("starting stdin listener");
        running.set(true);
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().start(() -> {
            running.set(false);
        }));
        Thread.ofVirtual().start(() -> {
            try (var scanner = new Scanner(System.in)) {
                while (running.get()) {
                    var line = scanner.nextLine();
                    if (line != null && !line.isBlank()) {
                        try {
                            processInput(line);
                        } catch (Throwable e) {
                            logger.error("failed to process stdin input", e);
                        }
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        logger.error("error when waiting", e);
                        return;
                    }
                }
            }
        });
    }

    private void processInput(String line) {
        logger.info("got line from stdin: {}", line);
        var cmd = Json.fromString(line, Command.class);
        logger.info("cmd: {}", cmd.command());

        var maybeRoom = this.masterHandler.roomHandlerByNumber(cmd.roomNumber());
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
}
