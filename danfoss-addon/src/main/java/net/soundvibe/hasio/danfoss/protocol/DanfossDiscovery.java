package net.soundvibe.hasio.danfoss.protocol;

import io.github.sonic_amiga.opensdg.java.GridConnection;
import io.github.sonic_amiga.opensdg.java.PairingConnection;
import io.github.sonic_amiga.opensdg.java.PeerConnection;
import io.github.sonic_amiga.opensdg.java.SDG;
import net.soundvibe.hasio.Json;
import net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConfig;
import net.soundvibe.hasio.danfoss.protocol.config.DominionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

public class DanfossDiscovery implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DanfossDiscovery.class);

    public final String oneTimeCode;
    public final DanfossBindingConfig bindingConfig;

    private final GridConnection conn;
    private final ScheduledExecutorService executorService;

    public DanfossDiscovery(String oneTimeCode, DanfossBindingConfig bindingConfig) {
        this.oneTimeCode = sanitizeOneTimeCode(Objects.requireNonNull(oneTimeCode));
        this.bindingConfig = bindingConfig;
        this.executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
        this.conn = new GridConnection(bindingConfig.privateKey(), this.executorService);
    }

    public DominionConfiguration.Response discover() {
        var myPeerId = Objects.requireNonNull(this.conn.getMyPeerId());
        var pairingConn = new PairingConnection();
        try {
            this.conn.connect(GridConnection.Danfoss);
            logger.info("Successfully connected to Danfoss grid");
            pairingConn.pairWithRemote(this.conn, this.oneTimeCode);
        } catch (RemoteException e) {
            throw new UncheckedIOException("Connection refused by peer; likely wrong OTP", e);
        } catch (IOException | InterruptedException | ExecutionException | GeneralSecurityException
                 | TimeoutException e) {
            throw new RuntimeException("Pairing failed: " , e);
        }

        var peerId = pairingConn.getPeerId();
        // we got PeerId so we can close the connection.
        pairingConn.close();

        var peerConnection = new PeerConnection();
        try {
            peerConnection.connectToRemote(this.conn, peerId, "dominion-configuration-1.0");
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to connect to the sender: ", e);
        }
        var request = new DominionConfiguration.Request(this.bindingConfig.userName(), SDG.bin2hex(myPeerId));
        int dataSize = 0;
        int offset = 0;
        byte[] data = null;

        try {
            var requestBytes = Json.toJsonString(request).getBytes(StandardCharsets.UTF_8);
            peerConnection.sendData(requestBytes);

            do {
                var chunkData = peerConnection.receiveData();
                if (chunkData == null) {
                    break;
                }
                var chunk = new DataInputStream(chunkData);
                int chunkSize = chunk.available();

                if (chunkSize > 8) {
                    // In chunked mode the data will arrive in several packets.
                    // The first one will contain the header, specifying full data length.
                    // The header has integer 0 in the beginning so that it's easily distinguished
                    // from JSON plaintext
                    if (chunk.readInt() == 0) {
                        // Size is little-endian here
                        dataSize = Integer.reverseBytes(chunk.readInt());
                        //logger.trace("Chunked mode; full size = {}", dataSize);
                        data = new byte[dataSize];
                        chunkSize -= 8; // We've consumed the header
                    } else {
                        // No header, go back to the beginning
                        chunk.reset();
                    }
                }

                if (dataSize == 0) {
                    // If the first packet didn't contain the header, this is not
                    // a chunked mode, so just use the complete length of this packet
                    // and we're done
                    dataSize = chunkSize;
                    //logger.trace("Raw mode; full size = {}", dataSize);
                    data = new byte[dataSize];
                }

                chunk.read(data, offset, chunkSize);
                offset += chunkSize;
            } while (offset < dataSize);
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to receive config: ", e);
        } finally {
            peerConnection.close();
        }

        if (data == null || data.length == 0) {
            logger.error("no data received");
            throw new RuntimeException("no data received");
        }
        var jsonString = new String(data, StandardCharsets.UTF_8);
        var parsedConfig = Json.fromString(jsonString, DominionConfiguration.Response.class);
        if (parsedConfig.housePeerId != null) {
            var housePeerId = parsedConfig.housePeerId;
            var houseName = parsedConfig.houseName;
            logger.info("Received IconWifi controller: peerId={}, houseName={}", housePeerId, houseName);
        }
        return parsedConfig;
    }

    @Override
    public void close() {
        this.executorService.close();
        this.conn.close();
    }

    private String sanitizeOneTimeCode(String code) {
        return code.replaceAll("-", "");
    }
}
