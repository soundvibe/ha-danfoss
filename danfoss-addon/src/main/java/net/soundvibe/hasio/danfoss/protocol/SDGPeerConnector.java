package net.soundvibe.hasio.danfoss.protocol;

import io.github.sonic_amiga.opensdg.java.Connection;
import io.github.sonic_amiga.opensdg.java.GridConnection;
import io.github.sonic_amiga.opensdg.java.SDG;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import net.soundvibe.hasio.danfoss.protocol.utils.SDGUtils;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.*;

public class SDGPeerConnector {

    private static final Logger logger = LoggerFactory.getLogger(SDGPeerConnector.class);

    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private final PacketHandler packetHandler;
    private final byte[] privateKey;
    private final ScheduledExecutorService scheduler;
    private byte[] peerId;
    private DeviSmartConnection connection;
    private @Nullable Future<?> reconnectReq;
    private @Nullable Future<?> watchdog;
    private long lastPacket = 0;

    SDGPeerConnector(PacketHandler packetHandler, byte[] privateKey, ScheduledExecutorService scheduler) {
        this.packetHandler = packetHandler;
        this.privateKey = privateKey;
        this.scheduler = scheduler;
    }

    public void initialize(String peerIdStr) {
        logger.trace("initialize()");

        peerId = SDGUtils.ParseKey(peerIdStr);
        if (peerId == null) {
            logger.warn("Peer ID is not set");
            return;
        }

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        connection = new DeviSmartConnection(this);

        watchdog = scheduler.scheduleAtFixedRate(() -> {
            if (connection == null || connection.getState() != Connection.State.CONNECTED) {
                return;
            }
            if (System.currentTimeMillis() - lastPacket > 30000) {
                logger.warn("Device is inactive during 30 seconds, reconnecting");
                singleThread.execute(() -> {
                    if (connection == null) {
                        return; // We are being disposed
                    }
                    connection.close();
                    scheduleReconnect();
                });
            } else if (System.currentTimeMillis() - lastPacket > 15000) {
                logger.warn("Device is inactive during 15 seconds, sending PING");
                this.packetHandler.ping();
            }
        }, 10, 10, TimeUnit.SECONDS);

        connect();
    }

    public void dispose() {
        logger.trace("dispose()");

        singleThread.execute(() -> {
            DeviSmartConnection conn = connection;
            connection = null; // This signals we are being disposed

            Future<?> reconnect = reconnectReq;
            if (reconnect != null) {
                reconnect.cancel(false);
                reconnectReq = null;
            }

            Future<?> wd = watchdog;
            if (wd != null) {
                wd.cancel(false);
                watchdog = null;
            }

            if (conn != null) {
                conn.close();
            }
        });
    }

    private void connect() {
        // In order not to mess up our connection state we need to make sure
        // that any two calls are never running concurrently. We use
        // singleThreadExecutorService for this purpose
        singleThread.execute(() -> {
            if (connection == null) {
                return; // Stale Reconnect request from deleted/disabled Thing
            }

            try {
                var grid = new GridConnection(privateKey, scheduler);
                grid.connect(GridConnection.Danfoss);
                logger.info("Connecting to peer {}", SDG.bin2hex(peerId));
                connection.connectToRemote(grid, peerId, Dominion.ProtocolName);
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                setOfflineStatus(e);
                return;
            }

            connection.asyncReceive();
            setOnlineStatus();
        });
    }

    public void setOnlineStatus() {
        if (connection != null) {
            logger.info("Connection established");
        }
    }

    public void setOfflineStatus(Throwable t) {
        String reason = t.getMessage();

        if (reason == null) {
            // Some errors might not have a reason
            if (t instanceof ClosedChannelException) {
                reason = "Peer not connected";
            } else if (t instanceof TimeoutException) {
                reason = "Communication timeout";
            } else {
                reason = t.toString();
            }
        }

        logger.warn("Device went offline: {}", reason);

        if (connection != null) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        logger.info("schedule reconnect");
        reconnectReq = scheduler.schedule(this::connect, 10, TimeUnit.SECONDS);
    }

    public void Send(byte[] data) {
        // Cache "connection" in order to avoid possible race condition
        // with dispose() zeroing it between test and usage
        DeviSmartConnection conn = connection;

        if (conn == null || conn.getState() != Connection.State.CONNECTED) {
            // Avoid "Failed to send data" warning if the connection hasn't been
            // connected yet. This may happen as OpenHAB sends REFRESH request for
            // every item right after the Thing has been initialized; it doesn't wait
            // for the Thing to go online.
            return;
        }

        try {
            conn.sendData(data);
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Failed to send data: {}", e.toString());
        }
    }

    public void SendPacket(Dominion.Packet pkt) {
        Send(pkt.getBuffer());
    }

    public void handlePacket(Dominion.Packet pkt) {
        lastPacket = System.currentTimeMillis();
        this.packetHandler.handlePacket(pkt);
    }
}
