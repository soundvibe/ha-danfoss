package net.soundvibe.hasio.danfoss.protocol;

import com.google.protobuf.ByteString;
import io.github.sonic_amiga.opensdg.java.SDG;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.PeerInfo;
import io.github.sonic_amiga.opensdg.protocol.generated.ForwardProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IncomingPeerConnectionTest {

    private static final byte[] TUNNEL_ID = "tunnel-42".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "Mdg-NaCl/binary";
    private static final byte HOLD = 1;
    private static final byte REPLY = 2;
    private static final byte ERROR = 3;
    private static final int TIMEOUT_MILLIS = 5_000;

    private ServerSocket forwardingServer;
    private ExecutorService remoteSide;
    private IncomingPeerConnection sut;

    @FunctionalInterface
    private interface RemoteScript<T> {
        T run(Socket socket) throws Exception;
    }

    @BeforeEach
    void setUp() throws IOException {
        forwardingServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        remoteSide = Executors.newSingleThreadExecutor();
        sut = new IncomingPeerConnection(SDG.createPrivateKey());
    }

    @AfterEach
    void tearDown() throws IOException {
        sut.close();
        remoteSide.shutdownNow();
        forwardingServer.close();
    }

    @Test
    void should_complete_the_handshake_and_exchange_data_in_both_directions() throws Exception {
        var forwardRequest = new ForwardProtocol.ForwardRequest[1];
        var received = new byte[1][];
        var remote = remote(socket -> {
            forwardRequest[0] = readForwardRequest(socket);
            writeForward(socket, HOLD, new byte[0]);
            writeForward(socket, REPLY, ForwardProtocol.ForwardReply.newBuilder().setSignature(SIGNATURE).build().toByteArray());
            var client = new TestMdgClient(socket);
            client.handshake();
            received[0] = client.receive();
            client.send("configuration".getBytes(StandardCharsets.UTF_8));
            return client;
        });

        var caller = sut.accept(peerInfo(), TIMEOUT_MILLIS);
        sut.sendData("request".getBytes(StandardCharsets.UTF_8));
        var reply = sut.receiveData();
        var client = remote.get(5, TimeUnit.SECONDS);

        assertEquals(0xF09D8C95, forwardRequest[0].getMagic());
        assertEquals(1, forwardRequest[0].getProtocolMajor());
        assertEquals(0, forwardRequest[0].getProtocolMinor());
        assertArrayEquals(TUNNEL_ID, forwardRequest[0].getTunnelId().toByteArray());
        assertEquals(SIGNATURE, forwardRequest[0].getSignature());
        assertArrayEquals(client.publicKey, caller);
        assertArrayEquals(new byte[]{0}, client.readyPayload, "REDY must carry a single zero byte");
        assertArrayEquals("request".getBytes(StandardCharsets.UTF_8), received[0]);
        assertArrayEquals("configuration".getBytes(StandardCharsets.UTF_8), reply);
        assertNull(sut.receiveData(), "closed connection is reported as end of data");
    }

    @Test
    void should_send_the_largest_payload_in_one_frame_and_reject_a_longer_one() throws Exception {
        var largest = new byte[IncomingPeerConnection.MAX_PAYLOAD_SIZE];
        new Random(1).nextBytes(largest);
        var received = new byte[1][];
        var remote = remote(socket -> {
            acceptForwarding(socket);
            var client = new TestMdgClient(socket);
            client.handshake();
            received[0] = client.receive();
            return client;
        });

        sut.accept(peerInfo(), TIMEOUT_MILLIS);
        sut.sendData(largest);
        var client = remote.get(5, TimeUnit.SECONDS);

        assertEquals(65_501, IncomingPeerConnection.MAX_PAYLOAD_SIZE);
        assertEquals(0xffff, client.lastFrameLength);
        assertArrayEquals(largest, received[0]);
        assertThrows(IllegalArgumentException.class,
                () -> sut.sendData(new byte[IncomingPeerConnection.MAX_PAYLOAD_SIZE + 1]));
    }

    @Test
    void should_reject_an_empty_forwarding_frame() {
        remote(socket -> {
            readForwardRequest(socket);
            socket.getOutputStream().write(new byte[]{0, 0});
            socket.getOutputStream().flush();
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Forwarding packet too short", error.getMessage());
    }

    @Test
    void should_reject_a_truncated_forwarding_frame() {
        remote(socket -> {
            readForwardRequest(socket);
            socket.getOutputStream().write(new byte[]{0, 10, REPLY, 1, 2});
            socket.getOutputStream().flush();
            return null;
        });

        assertThrows(EOFException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
    }

    @Test
    void should_report_the_code_of_a_forwarding_error() {
        remote(socket -> {
            readForwardRequest(socket);
            writeForward(socket, ERROR, ForwardProtocol.ForwardError.newBuilder().setCode(7).build().toByteArray());
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Forwarding refused: 7", error.getMessage());
    }

    @Test
    void should_reject_a_forwarding_reply_with_a_wrong_signature() {
        remote(socket -> {
            readForwardRequest(socket);
            writeForward(socket, REPLY, ForwardProtocol.ForwardReply.newBuilder().setSignature("other").build().toByteArray());
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Bad forwarding signature: other", error.getMessage());
    }

    @Test
    void should_reject_an_unknown_forwarding_packet() {
        remote(socket -> {
            readForwardRequest(socket);
            writeForward(socket, (byte) 9, new byte[0]);
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Unexpected forwarding packet 9", error.getMessage());
    }

    @Test
    void should_not_let_hold_packets_extend_the_deadline() {
        remote(socket -> {
            readForwardRequest(socket);
            while (!Thread.currentThread().isInterrupted()) {
                writeForward(socket, HOLD, new byte[0]);
                Thread.sleep(50);
            }
            return null;
        });

        long start = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> sut.accept(peerInfo(), 500));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= 450 && elapsedMillis < 3_000, "took " + elapsedMillis + " ms");
    }

    @Test
    void should_reject_a_replayed_data_packet() throws Exception {
        remote(socket -> {
            acceptForwarding(socket);
            var client = new TestMdgClient(socket);
            client.handshake();
            var frame = client.send("once".getBytes(StandardCharsets.UTF_8));
            client.sendRaw(frame);
            return waitForClose(socket);
        });

        sut.accept(peerInfo(), TIMEOUT_MILLIS);

        assertArrayEquals("once".getBytes(StandardCharsets.UTF_8), sut.receiveData());
        var error = assertThrows(ProtocolException.class, () -> sut.receiveData());
        assertTrue(error.getMessage().startsWith("Replayed or out-of-order packet nonce"), error.getMessage());
    }

    @Test
    void should_reject_a_hello_that_is_not_encrypted_for_our_key() {
        remote(socket -> {
            acceptForwarding(socket);
            socket.getOutputStream().write(TestMdgClient.frame("TELL", new byte[0]));
            var hello = new byte[32 + 8 + 80];
            new Random(2).nextBytes(hello);
            socket.getOutputStream().write(TestMdgClient.frame("HELO", hello));
            socket.getOutputStream().flush();
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Decryption failed", error.getMessage());
    }

    @Test
    void should_reject_a_packet_that_does_not_belong_to_the_handshake() {
        remote(socket -> {
            acceptForwarding(socket);
            socket.getOutputStream().write(TestMdgClient.frame("MESG", new byte[40]));
            socket.getOutputStream().flush();
            return waitForClose(socket);
        });

        var error = assertThrows(ProtocolException.class, () -> sut.accept(peerInfo(), TIMEOUT_MILLIS));
        assertEquals("Unexpected packet MESG, expected HELO", error.getMessage());
    }

    private PeerInfo peerInfo() {
        return PeerInfo.newBuilder()
                .setPeerId("caller")
                .setServer(PeerInfo.Endpoint.newBuilder().setHost("127.0.0.1").setPort(forwardingServer.getLocalPort()))
                .setTunnelId(ByteString.copyFrom(TUNNEL_ID))
                .build();
    }

    /** Plays the forwarding server and the calling peer on the other end of the socket. */
    private <T> Future<T> remote(RemoteScript<T> script) {
        return remoteSide.submit(() -> {
            try (var socket = forwardingServer.accept()) {
                socket.setSoTimeout(TIMEOUT_MILLIS);
                return script.run(socket);
            }
        });
    }

    private static void acceptForwarding(Socket socket) throws IOException {
        readForwardRequest(socket);
        writeForward(socket, REPLY, ForwardProtocol.ForwardReply.newBuilder().setSignature(SIGNATURE).build().toByteArray());
    }

    private static ForwardProtocol.ForwardRequest readForwardRequest(Socket socket) throws IOException {
        var in = new DataInputStream(socket.getInputStream());
        var frame = new byte[in.readUnsignedShort()];
        in.readFully(frame);
        assertEquals(0, frame[0], "forwarding request type");
        return ForwardProtocol.ForwardRequest.parseFrom(Arrays.copyOfRange(frame, 1, frame.length));
    }

    private static void writeForward(Socket socket, byte type, byte[] body) throws IOException {
        socket.getOutputStream().write(ByteBuffer.allocate(3 + body.length).putShort((short) (1 + body.length)).put(type).put(body).array());
        socket.getOutputStream().flush();
    }

    /** Keeps the socket open until the side under test closes it or gives up. */
    private static Void waitForClose(Socket socket) {
        try {
            while (socket.getInputStream().read() >= 0) {
                // discard
            }
        } catch (IOException e) {
            // closed or timed out: either way the script is done
        }
        return null;
    }
}
