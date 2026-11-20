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
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        var result = new CompletableFuture<DominionConfiguration.Response>();
        var outgoingError = new AtomicReference<Throwable>();
        var incomingError = new AtomicReference<Throwable>();
        var pending = new AtomicInteger(2);
        Runnable failIfBothFailed = () -> {
            if (pending.decrementAndGet() == 0) {
                var error = outgoingError.get();
                error.addSuppressed(incomingError.get());
                result.completeExceptionally(error);
            }
        };
        Thread.ofVirtual().name("danfoss-discovery-outgoing").start(() -> {
            try {
                result.complete(receiveConfigByCallingSender(peerId));
            } catch (Throwable e) {
                outgoingError.set(e);
                failIfBothFailed.run();
            }
        });
        var incoming = Thread.ofVirtual().name("danfoss-discovery-incoming").start(() -> {
            try {
                result.complete(receiveConfigFromSenderCall(peerId));
            } catch (Throwable e) {
                incomingError.set(e);
                failIfBothFailed.run();
            }
        });

        DominionConfiguration.Response parsedConfig;
        try {
            parsedConfig = result.get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for the configuration", e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for the configuration from the sender", e);
        } finally {
            this.conn.stopAcceptingCalls();
            incoming.interrupt();
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
    private DominionConfiguration.Response receiveConfigByCallingSender(byte[] peerId) {
        var peerConnection = new PeerConnection();
        try {
            peerConnection.connectToRemote(this.conn, peerId, CONFIGURATION_PROTOCOL);
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to connect to the sender: ", e);
        }

        try {
            peerConnection.sendData(configurationRequest());
            return receiveConfig(peerConnection::receiveData);
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed to receive config: ", e);
        } finally {
            peerConnection.close();
        }
    }

    /**
     * iOS flow: the sender calls us. The grid routes the call only because the sender was
     * registered as our pairing while pairing (see {@link RegisteringPairingConnection}).
     */
    private DominionConfiguration.Response receiveConfigFromSenderCall(byte[] peerId) throws Exception {
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

            try (var peerConnection = new IncomingPeerConnection(this.bindingConfig.privateKey())) {
                var caller = peerConnection.accept(call.getPeer(), INCOMING_CONNECTION_TIMEOUT_MILLIS);
                if (!Arrays.equals(caller, peerId)) {
                    throw new ProtocolException("Incoming call authenticated as " + SDG.bin2hex(caller)
                            + " instead of the sender");
                }
                logger.info("Sender called back, requesting the configuration");
                peerConnection.sendData(configurationRequest());
                return receiveConfig(() -> {
                    var packet = peerConnection.receiveData();
                    return packet == null ? null : new ByteArrayInputStream(packet);
                });
            }
        }
    }

    private byte[] configurationRequest() {
        var request = new DominionConfiguration.Request(this.bindingConfig.userName(), SDG.bin2hex(this.conn.getMyPeerId()));
        return Json.toJsonString(request).getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface PacketSource {
        InputStream next() throws IOException, InterruptedException, ExecutionException, TimeoutException;
    }

    private static DominionConfiguration.Response receiveConfig(PacketSource packets)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        int dataSize = 0;
        int offset = 0;
        byte[] data = null;

        do {
            var chunkData = packets.next();
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

        if (data == null || data.length == 0) {
            logger.error("no data received");
            throw new RuntimeException("no data received");
        }
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
