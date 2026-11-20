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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DanfossDiscovery implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DanfossDiscovery.class);

    private static final String CONFIGURATION_PROTOCOL = "dominion-configuration-1.0";
    private static final long INCOMING_CALL_TIMEOUT_SECONDS = 30;
    private static final long DISCOVERY_TIMEOUT_SECONDS = 60;
    private static final int INCOMING_CONNECTION_TIMEOUT_MILLIS = 10_000;

    public final String oneTimeCode;
    public final DanfossBindingConfig bindingConfig;

    private final DiscoveryGridConnection conn;
    private final ScheduledExecutorService executorService;
    private boolean closed;

    public DanfossDiscovery(String oneTimeCode, DanfossBindingConfig bindingConfig) {
        this.oneTimeCode = sanitizeOneTimeCode(Objects.requireNonNull(oneTimeCode));
        this.bindingConfig = bindingConfig;
        this.executorService = Executors.newScheduledThreadPool(16, Thread.ofVirtual().factory());
        this.conn = new DiscoveryGridConnection(bindingConfig.privateKey(), this.executorService);
    }

    public DominionConfiguration.Response discover() {
        Objects.requireNonNull(this.conn.getMyPeerId());
        var pairingConn = new RegisteringPairingConnection(this.conn);
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
        pairingConn.close();
        if (peerId == null) {
            throw new IllegalStateException("Expected to get server peerId but got null instead");
        }
        logger.info("Got server peerId: {}", SDG.bin2hex(peerId));

        // The Android app waits for us to call it, while the iOS app calls us right after pairing.
        // Both ways are tried at the same time and the first one to deliver the configuration wins.
        // This method owns both attempts: whatever the outcome, both are cancelled and both
        // connections are closed before it returns.
        var outgoingConnection = new PeerConnection();
        var incomingConnection = new IncomingPeerConnection(this.bindingConfig.privateKey());
        List<Callable<DominionConfiguration.Response>> attempts = List.of(
                () -> receiveConfigByCallingSender(peerId, outgoingConnection),
                () -> receiveConfigFromSenderCall(peerId, incomingConnection));

        DominionConfiguration.Response parsedConfig;
        try {
            parsedConfig = FirstSuccess.await(attempts, DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS, () -> {
                this.conn.stopAcceptingCalls();
                outgoingConnection.close();
                incomingConnection.close();
            });
        } catch (FirstSuccess.AllFailedException e) {
            // Report the failure of our own call, as before; the other attempt's failure is attached to it.
            var errors = e.errors();
            var error = errors.get(0);
            errors.stream().skip(1).filter(other -> other != error).forEach(error::addSuppressed);
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to receive config: ", error);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for the configuration from the sender", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for the configuration", e);
        }

        if (parsedConfig.housePeerId != null) {
            var housePeerId = parsedConfig.housePeerId;
            var houseName = parsedConfig.houseName;
            logger.info("Received IconWifi controller: peerId={}, houseName={}", housePeerId, houseName);
        }
        return parsedConfig;
    }

    /**
     * Original flow: call the sender and ask it for the configuration. Works with the Android app.
     */
    private DominionConfiguration.Response receiveConfigByCallingSender(byte[] peerId, PeerConnection peerConnection)
            throws InterruptedException {
        try {
            peerConnection.connectToRemote(this.conn, peerId, CONFIGURATION_PROTOCOL);
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to connect to the sender: ", e);
        }

        try {
            if (Thread.currentThread().isInterrupted()) {
                // the other attempt has already won; do not ask the sender again
                throw new InterruptedException();
            }
            logger.info("Connected to the sender, requesting the configuration");
            peerConnection.sendData(configurationRequest());
            return parseConfig(ConfigurationReader.read(peerConnection::receiveData));
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to receive config: ", e);
        } finally {
            peerConnection.close();
        }
    }

    /**
     * iOS flow: the sender calls us. The grid routes the call only because the sender was
     * registered as our pairing while pairing (see {@link RegisteringPairingConnection}).
     */
    private DominionConfiguration.Response receiveConfigFromSenderCall(byte[] peerId, IncomingPeerConnection peerConnection)
            throws Exception {
        var expectedPeer = SDG.bin2hex(peerId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INCOMING_CALL_TIMEOUT_SECONDS);
        while (true) {
            long left = deadline - System.nanoTime();
            var call = left > 0 ? this.conn.pollIncomingCall(left, TimeUnit.NANOSECONDS) : null;
            if (call == null) {
                throw new TimeoutException("The sender did not call back");
            }
            boolean fromSender = CONFIGURATION_PROTOCOL.equals(call.getProtocol())
                    && expectedPeer.equalsIgnoreCase(call.getPeer().getPeerId());
            this.conn.replyToIncomingCall(call, fromSender);
            if (!fromSender) {
                logger.warn("Refused unexpected incoming call from {} (protocol {})",
                        call.getPeer().getPeerId(), call.getProtocol());
                continue;
            }

            try {
                var caller = peerConnection.accept(call.getPeer(), INCOMING_CONNECTION_TIMEOUT_MILLIS);
                if (!Arrays.equals(caller, peerId)) {
                    throw new ProtocolException("Incoming call authenticated as " + SDG.bin2hex(caller)
                            + " instead of the sender");
                }
                logger.info("Sender called back, requesting the configuration");
                peerConnection.sendData(configurationRequest());
                return parseConfig(ConfigurationReader.read(() -> {
                    var packet = peerConnection.receiveData();
                    return packet == null ? null : new ByteArrayInputStream(packet);
                }));
            } finally {
                peerConnection.close();
            }
        }
    }

    private byte[] configurationRequest() {
        var request = new DominionConfiguration.Request(this.bindingConfig.userName(), SDG.bin2hex(this.conn.getMyPeerId()));
        return Json.toJsonString(request).getBytes(StandardCharsets.UTF_8);
    }

    private static DominionConfiguration.Response parseConfig(byte[] data) {
        var jsonString = new String(data, StandardCharsets.UTF_8);
        return Json.fromString(jsonString, DominionConfiguration.Response.class);
    }

    /**
     * Registers the sender with the grid as soon as the pairing tunnel is up, i.e. before the
     * pairing completes: the iOS app calls back immediately after the pairing result, and the grid
     * refuses calls from peers that are not on our list of pairings.
     */
    private static final class RegisteringPairingConnection extends PairingConnection {
        private final DiscoveryGridConnection grid;

        private RegisteringPairingConnection(DiscoveryGridConnection grid) {
            this.grid = grid;
        }

        @Override
        public void onReadyPacket() throws IOException, InterruptedException, ExecutionException {
            super.onReadyPacket();
            try {
                grid.registerPairings(List.of(getPeerId()));
            } catch (TimeoutException e) {
                throw new IOException("Failed to register the pairing with the grid", e);
            }
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.executorService.close();
        this.conn.close();
    }

    private String sanitizeOneTimeCode(String code) {
        return code.replaceAll("-", "");
    }
}
