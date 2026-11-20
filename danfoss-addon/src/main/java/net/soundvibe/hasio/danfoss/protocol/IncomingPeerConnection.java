package net.soundvibe.hasio.danfoss.protocol;

import com.google.protobuf.ByteString;
import com.neilalexander.jnacl.crypto.curve25519xsalsa20poly1305;
import io.github.sonic_amiga.opensdg.java.SDG;
import io.github.sonic_amiga.opensdg.protocol.Control;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.PeerInfo;
import io.github.sonic_amiga.opensdg.protocol.generated.ForwardProtocol;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The accepting side of a peer-to-peer tunnel, used when a remote peer calls us (grid message 12).
 * <p>
 * opensdg-java only implements the calling side of the MDG binary protocol (a CurveCP variant).
 * This class is its mirror image: after joining the forwarded tunnel it answers TELL with WELC,
 * HELO with COOK and VOCH with REDY, and then exchanges MESG packets.
 * <p>
 * Like CurveCP, the caller numbers its packets (HELO, VOCH, MESG) with a strictly increasing nonce.
 * Packets with a repeated or lower nonce are rejected, so a captured packet cannot be replayed
 * into the same session.
 * <p>
 * Every operation ({@link #accept}, {@link #sendData}, {@link #receiveData}) has a hard deadline.
 * A socket read timeout only limits a single read, so a peer that trickles bytes, or one that stops
 * reading while we write, could hold an operation open indefinitely. Instead a watchdog closes the
 * socket when the deadline expires, which unblocks reads and writes alike. After a timeout the
 * connection is closed and cannot be used any more.
 */
final class IncomingPeerConnection implements AutoCloseable {

    private static final int PACKET_MAGIC = 0xf09f909f;
    private static final int FORWARD_MAGIC = 0xF09D8C95;
    private static final String FORWARD_SIGNATURE = "Mdg-NaCl/binary";
    private static final byte MSG_FORWARD_REQUEST = 0;
    private static final byte MSG_FORWARD_HOLD = 1;
    private static final byte MSG_FORWARD_REPLY = 2;
    private static final byte MSG_FORWARD_ERROR = 3;

    private static final int CMD_TELL = command("TELL");
    private static final int CMD_WELC = command("WELC");
    private static final int CMD_HELO = command("HELO");
    private static final int CMD_COOK = command("COOK");
    private static final int CMD_VOCH = command("VOCH");
    private static final int CMD_REDY = command("REDY");
    private static final int CMD_MESG = command("MESG");

    private static final int KEY_SIZE = SDG.KEY_SIZE;
    private static final int MAC_SIZE = 16;
    private static final int BOX_ZERO_BYTES = 16;
    private static final int ZERO_BYTES = 32;
    private static final int SHORT_NONCE_SIZE = 8;
    private static final int LONG_NONCE_SIZE = 16;
    private static final int COOKIE_SIZE = 96;
    private static final int HELO_BOX_SIZE = 80;
    private static final int VOUCH_BOX_SIZE = MAC_SIZE + KEY_SIZE;

    /** Every frame starts with a 16-bit length, so this is the largest frame body. */
    private static final int MAX_FRAME_SIZE = 0xffff;
    /** Magic and command that precede the data of every MDG packet. */
    private static final int PACKET_HEADER_SIZE = 8;
    private static final int MESG_LENGTH_SIZE = 2;
    /** The largest payload that still fits into a single MESG frame (65,501 bytes). */
    static final int MAX_PAYLOAD_SIZE =
            MAX_FRAME_SIZE - PACKET_HEADER_SIZE - SHORT_NONCE_SIZE - MAC_SIZE - MESG_LENGTH_SIZE;

    private static final int RUNNING = 0;
    private static final int DONE = 1;
    private static final int EXPIRED = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] privateKey;
    private final byte[] publicKey;
    private final Socket socket = new Socket();
    private DataInputStream in;
    private OutputStream out;
    private byte[] sharedKey;
    private long nonce;
    private boolean peerNonceSeen;
    private long lastPeerNonce;
    private int timeoutMillis;

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    IncomingPeerConnection(byte[] privateKey) {
        this.privateKey = privateKey.clone();
        this.publicKey = SDG.calcPublicKey(this.privateKey);
    }

    /**
     * Joins the tunnel announced in an accepted incoming call and completes the handshake.
     * The timeout covers the whole operation, and later each {@link #sendData} and {@link #receiveData} call.
     *
     * @return peer ID (public key) of the caller
     */
    byte[] accept(PeerInfo peer, int timeoutMillis) throws IOException {
        this.timeoutMillis = timeoutMillis;
        return withDeadline(() -> {
            socket.connect(new InetSocketAddress(peer.getServer().getHost(), peer.getServer().getPort()), timeoutMillis);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = socket.getOutputStream();
            forward(peer.getTunnelId());
            return handshake();
        });
    }

    void sendData(byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "Payload of " + payload.length + " bytes exceeds the limit of " + MAX_PAYLOAD_SIZE);
        }
        var plain = ByteBuffer.allocate(MESG_LENGTH_SIZE + payload.length);
        plain.putShort((short) payload.length);
        plain.put(payload);
        withDeadline(() -> {
            writePacket(CMD_MESG, encryptWithNonce("CurveCP-server-M", plain.array()));
            return null;
        });
    }

    /**
     * @return the next data packet, or null when the peer has closed the connection
     */
    byte[] receiveData() throws IOException {
        return withDeadline(() -> {
            Packet packet;
            try {
                packet = readPacket();
            } catch (EOFException e) {
                return null;
            }
            expect(packet, CMD_MESG, SHORT_NONCE_SIZE + MAC_SIZE + MESG_LENGTH_SIZE);
            var data = packet.data();
            long packetNonce = ByteBuffer.wrap(data, 0, SHORT_NONCE_SIZE).getLong();
            var plain = openAfterNm(Arrays.copyOfRange(data, SHORT_NONCE_SIZE, data.length),
                    shortNonce("CurveCP-client-M", packetNonce));
            acceptPeerNonce(packetNonce);
            int length = ByteBuffer.wrap(plain, 0, MESG_LENGTH_SIZE).getShort() & 0xffff;
            if (length > plain.length - MESG_LENGTH_SIZE) {
                throw new ProtocolException("Invalid MESG payload length " + length);
            }
            return Arrays.copyOfRange(plain, MESG_LENGTH_SIZE, MESG_LENGTH_SIZE + length);
        });
    }

    /**
     * Runs blocking socket I/O that must finish within the timeout given to {@link #accept}.
     * When the time is up, the watchdog closes the socket; the blocked read or write then fails
     * and is reported as a timeout.
     */
    private <T> T withDeadline(IoAction<T> action) throws IOException {
        var state = new AtomicInteger(RUNNING);
        long millis = timeoutMillis;
        var watchdog = Thread.ofVirtual().name("danfoss-incoming-deadline").start(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;     // the operation finished in time
            }
            if (state.compareAndSet(RUNNING, EXPIRED)) {
                close();
            }
        });
        try {
            T result = action.run();
            if (!state.compareAndSet(RUNNING, DONE)) {
                throw new SocketTimeoutException("Timed out after " + millis + " ms");
            }
            return result;
        } catch (IOException e) {
            if (state.get() == EXPIRED && !(e instanceof SocketTimeoutException)) {
                var timeout = new SocketTimeoutException("Timed out after " + millis + " ms");
                timeout.initCause(e);
                throw timeout;
            }
            throw e;
        } finally {
            state.compareAndSet(RUNNING, DONE);
            watchdog.interrupt();
        }
    }

    /**
     * Closes the socket. Safe to call from another thread: it unblocks pending reads and writes.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing to do
        }
    }

    private void forward(ByteString tunnelId) throws IOException {
        var request = ForwardProtocol.ForwardRequest.newBuilder()
                .setMagic(FORWARD_MAGIC)
                .setProtocolMajor(Control.PROTOCOL_VERSION_MAJOR)
                .setProtocolMinor(Control.PROTOCOL_VERSION_MINOR)
                .setTunnelId(tunnelId)
                .setSignature(FORWARD_SIGNATURE)
                .build()
                .toByteArray();
        var frame = ByteBuffer.allocate(3 + request.length);
        frame.putShort((short) (1 + request.length));
        frame.put(MSG_FORWARD_REQUEST);
        frame.put(request);
        out.write(frame.array());
        out.flush();

        while (true) {
            var reply = readFrame();
            if (reply.length == 0) {
                throw new ProtocolException("Forwarding packet too short");
            }
            var payload = Arrays.copyOfRange(reply, 1, reply.length);
            switch (reply[0]) {
                case MSG_FORWARD_HOLD -> {
                    // the other side has not joined yet, keep waiting (the deadline of accept() still applies)
                }
                case MSG_FORWARD_REPLY -> {
                    var signature = ForwardProtocol.ForwardReply.parseFrom(payload).getSignature();
                    if (!FORWARD_SIGNATURE.equals(signature)) {
                        throw new ProtocolException("Bad forwarding signature: " + signature);
                    }
                    return;
                }
                case MSG_FORWARD_ERROR -> throw new ProtocolException(
                        "Forwarding refused: " + ForwardProtocol.ForwardError.parseFrom(payload).getCode());
                default -> throw new ProtocolException("Unexpected forwarding packet " + reply[0]);
            }
        }
    }

    private byte[] handshake() throws IOException {
        var packet = readPacket();
        if (packet.command() == CMD_TELL) {
            writePacket(CMD_WELC, publicKey);
            packet = readPacket();
        }

        // HELO: client's short-term key, nonce and a box of zeros
        expect(packet, CMD_HELO, KEY_SIZE + SHORT_NONCE_SIZE + HELO_BOX_SIZE);
        var helo = packet.data();
        var clientShortTermKey = Arrays.copyOfRange(helo, 0, KEY_SIZE);
        long heloNonce = ByteBuffer.wrap(helo, KEY_SIZE, SHORT_NONCE_SIZE).getLong();
        open(Arrays.copyOfRange(helo, KEY_SIZE + SHORT_NONCE_SIZE, KEY_SIZE + SHORT_NONCE_SIZE + HELO_BOX_SIZE),
                shortNonce("CurveCP-client-H", heloNonce), clientShortTermKey, privateKey);
        acceptPeerNonce(heloNonce);

        // COOK: our short-term key and a cookie, boxed with our long-term key
        var shortTermPublic = new byte[KEY_SIZE];
        var shortTermPrivate = new byte[KEY_SIZE];
        curve25519xsalsa20poly1305.crypto_box_keypair(shortTermPublic, shortTermPrivate);
        var cookie = randomBytes(COOKIE_SIZE);
        var cookNonce = randomBytes(LONG_NONCE_SIZE);
        var cookBox = seal(concat(shortTermPublic, cookie), longNonce("CurveCPK", cookNonce),
                clientShortTermKey, privateKey);
        writePacket(CMD_COOK, concat(cookNonce, cookBox));

        // VOCH: cookie, then the client's long-term key with a vouch for its short-term key
        packet = readPacket();
        expect(packet, CMD_VOCH, COOKIE_SIZE + SHORT_NONCE_SIZE + MAC_SIZE + KEY_SIZE + LONG_NONCE_SIZE + VOUCH_BOX_SIZE);
        var voch = packet.data();
        if (!MessageDigest.isEqual(cookie, Arrays.copyOfRange(voch, 0, COOKIE_SIZE))) {
            throw new ProtocolException("VOCH carries a wrong cookie");
        }
        sharedKey = new byte[KEY_SIZE];
        curve25519xsalsa20poly1305.crypto_box_beforenm(sharedKey, clientShortTermKey, shortTermPrivate);
        long vochNonce = ByteBuffer.wrap(voch, COOKIE_SIZE, SHORT_NONCE_SIZE).getLong();
        var vouch = openAfterNm(Arrays.copyOfRange(voch, COOKIE_SIZE + SHORT_NONCE_SIZE, voch.length),
                shortNonce("CurveCP-client-I", vochNonce));
        acceptPeerNonce(vochNonce);
        var clientKey = Arrays.copyOfRange(vouch, 0, KEY_SIZE);
        var innerNonce = Arrays.copyOfRange(vouch, KEY_SIZE, KEY_SIZE + LONG_NONCE_SIZE);
        var innerBox = Arrays.copyOfRange(vouch, KEY_SIZE + LONG_NONCE_SIZE, KEY_SIZE + LONG_NONCE_SIZE + VOUCH_BOX_SIZE);
        var vouchedKey = open(innerBox, longNonce("CurveCPV", innerNonce), clientKey, privateKey);
        if (!MessageDigest.isEqual(vouchedKey, clientShortTermKey)) {
            throw new ProtocolException("VOCH does not vouch for the HELO key");
        }

        // REDY carries a single zero byte, like the one sent by the grid server.
        // The iOS app drops the call when REDY is empty.
        writePacket(CMD_REDY, encryptWithNonce("CurveCP-server-R", new byte[]{0}));
        return clientKey;
    }

    /**
     * The caller's nonces must strictly increase from HELO through VOCH to every MESG.
     * Called only after the packet has been authenticated.
     */
    private void acceptPeerNonce(long packetNonce) throws ProtocolException {
        if (peerNonceSeen && Long.compareUnsigned(packetNonce, lastPeerNonce) <= 0) {
            throw new ProtocolException("Replayed or out-of-order packet nonce " + Long.toUnsignedString(packetNonce));
        }
        peerNonceSeen = true;
        lastPeerNonce = packetNonce;
    }

    private byte[] encryptWithNonce(String prefix, byte[] plain) throws ProtocolException {
        long packetNonce = nonce++;
        var box = sealAfterNm(plain, shortNonce(prefix, packetNonce));
        return ByteBuffer.allocate(SHORT_NONCE_SIZE + box.length).putLong(packetNonce).put(box).array();
    }

    private record Packet(int command, byte[] data) {
    }

    private void writePacket(int command, byte[] data) throws IOException {
        if (data.length > MAX_FRAME_SIZE - PACKET_HEADER_SIZE) {
            throw new ProtocolException("Packet " + commandName(command) + " of " + data.length + " bytes does not fit into a frame");
        }
        var packet = ByteBuffer.allocate(2 + PACKET_HEADER_SIZE + data.length);
        packet.putShort((short) (PACKET_HEADER_SIZE + data.length));
        packet.putInt(PACKET_MAGIC);
        packet.putInt(command);
        packet.put(data);
        out.write(packet.array());
        out.flush();
    }

    private Packet readPacket() throws IOException {
        var frame = readFrame();
        if (frame.length < PACKET_HEADER_SIZE) {
            throw new ProtocolException("Packet too short");
        }
        var buffer = ByteBuffer.wrap(frame);
        if (buffer.getInt() != PACKET_MAGIC) {
            throw new ProtocolException("Bad packet magic");
        }
        int command = buffer.getInt();
        return new Packet(command, Arrays.copyOfRange(frame, PACKET_HEADER_SIZE, frame.length));
    }

    private byte[] readFrame() throws IOException {
        int length = in.readUnsignedShort();
        var frame = new byte[length];
        in.readFully(frame);
        return frame;
    }

    private static void expect(Packet packet, int command, int minimumSize) throws ProtocolException {
        if (packet.command() != command) {
            throw new ProtocolException("Unexpected packet " + commandName(packet.command())
                    + ", expected " + commandName(command));
        }
        if (packet.data().length < minimumSize) {
            throw new ProtocolException("Packet " + commandName(command) + " too short");
        }
    }

    private static byte[] seal(byte[] plain, byte[] boxNonce, byte[] theirKey, byte[] ourKey) throws ProtocolException {
        var buffer = new byte[ZERO_BYTES + plain.length];
        System.arraycopy(plain, 0, buffer, ZERO_BYTES, plain.length);
        if (curve25519xsalsa20poly1305.crypto_box(buffer, buffer, boxNonce, theirKey, ourKey) != 0) {
            throw new ProtocolException("Encryption failed");
        }
        return Arrays.copyOfRange(buffer, BOX_ZERO_BYTES, buffer.length);
    }

    private static byte[] open(byte[] box, byte[] boxNonce, byte[] theirKey, byte[] ourKey) throws ProtocolException {
        var buffer = new byte[BOX_ZERO_BYTES + box.length];
        System.arraycopy(box, 0, buffer, BOX_ZERO_BYTES, box.length);
        if (curve25519xsalsa20poly1305.crypto_box_open(buffer, buffer, boxNonce, theirKey, ourKey) != 0) {
            throw new ProtocolException("Decryption failed");
        }
        return Arrays.copyOfRange(buffer, ZERO_BYTES, buffer.length);
    }

    private byte[] sealAfterNm(byte[] plain, byte[] boxNonce) throws ProtocolException {
        var buffer = new byte[ZERO_BYTES + plain.length];
        System.arraycopy(plain, 0, buffer, ZERO_BYTES, plain.length);
        if (curve25519xsalsa20poly1305.crypto_box_afternm(buffer, buffer, boxNonce, sharedKey) != 0) {
            throw new ProtocolException("Encryption failed");
        }
        return Arrays.copyOfRange(buffer, BOX_ZERO_BYTES, buffer.length);
    }

    private byte[] openAfterNm(byte[] box, byte[] boxNonce) throws ProtocolException {
        var buffer = new byte[BOX_ZERO_BYTES + box.length];
        System.arraycopy(box, 0, buffer, BOX_ZERO_BYTES, box.length);
        if (curve25519xsalsa20poly1305.crypto_box_open_afternm(buffer, buffer, boxNonce, sharedKey) != 0) {
            throw new ProtocolException("Decryption failed");
        }
        return Arrays.copyOfRange(buffer, ZERO_BYTES, buffer.length);
    }

    private static byte[] shortNonce(String prefix, long value) {
        return ByteBuffer.allocate(24).put(prefix.getBytes(StandardCharsets.US_ASCII)).putLong(value).array();
    }

    private static byte[] longNonce(String prefix, byte[] value) {
        return ByteBuffer.allocate(24).put(prefix.getBytes(StandardCharsets.US_ASCII)).put(value).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] randomBytes(int size) {
        var bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static int command(String name) {
        return ByteBuffer.wrap(name.getBytes(StandardCharsets.US_ASCII)).getInt();
    }

    private static String commandName(int command) {
        return new String(ByteBuffer.allocate(4).putInt(command).array(), StandardCharsets.US_ASCII);
    }
}
