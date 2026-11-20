package net.soundvibe.hasio.danfoss.protocol;

import com.neilalexander.jnacl.crypto.curve25519xsalsa20poly1305;
import io.github.sonic_amiga.opensdg.java.SDG;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Minimal calling side of the MDG binary protocol for tests: what a phone does after the forwarding
 * server has joined both ends (TELL, HELO, VOCH, then MESG packets).
 */
final class TestMdgClient {

    private static final int MAGIC = 0xf09f909f;
    private static final SecureRandom RANDOM = new SecureRandom();

    final byte[] privateKey = SDG.createPrivateKey();
    final byte[] publicKey = SDG.calcPublicKey(privateKey);
    private final DataInputStream in;
    private final OutputStream out;
    private byte[] sharedKey;
    private long nonce;
    byte[] serverKey;
    byte[] readyPayload;
    int lastFrameLength;

    TestMdgClient(Socket socket) throws IOException {
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    void handshake() throws IOException {
        writePacket("TELL", new byte[0]);
        serverKey = readPacket("WELC");

        var shortTermPublic = new byte[32];
        var shortTermPrivate = new byte[32];
        curve25519xsalsa20poly1305.crypto_box_keypair(shortTermPublic, shortTermPrivate);
        long heloNonce = nonce++;
        var zeros = seal(new byte[64], shortNonce("CurveCP-client-H", heloNonce), serverKey, shortTermPrivate);
        writePacket("HELO", concat(shortTermPublic, longBytes(heloNonce), zeros));

        var cook = readPacket("COOK");
        var cookPlain = open(Arrays.copyOfRange(cook, 16, cook.length),
                longNonce("CurveCPK", Arrays.copyOfRange(cook, 0, 16)), serverKey, shortTermPrivate);
        var serverShortTermKey = Arrays.copyOfRange(cookPlain, 0, 32);
        var cookie = Arrays.copyOfRange(cookPlain, 32, 128);
        sharedKey = new byte[32];
        curve25519xsalsa20poly1305.crypto_box_beforenm(sharedKey, serverShortTermKey, shortTermPrivate);

        var vouchNonce = new byte[16];
        RANDOM.nextBytes(vouchNonce);
        var vouch = seal(shortTermPublic, longNonce("CurveCPV", vouchNonce), serverKey, privateKey);
        long vochNonce = nonce++;
        var initiate = sealAfterNm(concat(publicKey, vouchNonce, vouch, new byte[]{0}), shortNonce("CurveCP-client-I", vochNonce));
        writePacket("VOCH", concat(cookie, longBytes(vochNonce), initiate));

        var ready = readPacket("REDY");
        readyPayload = openAfterNm(Arrays.copyOfRange(ready, 8, ready.length),
                shortNonce("CurveCP-server-R", ByteBuffer.wrap(ready).getLong()));
    }

    /** Sends a data packet and returns the raw frame, so that tests can replay it. */
    byte[] send(byte[] payload) throws IOException {
        long packetNonce = nonce++;
        var plain = ByteBuffer.allocate(2 + payload.length).putShort((short) payload.length).put(payload).array();
        var frame = frame("MESG", concat(longBytes(packetNonce), sealAfterNm(plain, shortNonce("CurveCP-client-M", packetNonce))));
        sendRaw(frame);
        return frame;
    }

    void sendRaw(byte[] frame) throws IOException {
        out.write(frame);
        out.flush();
    }

    byte[] receive() throws IOException {
        var mesg = readPacket("MESG");
        var plain = openAfterNm(Arrays.copyOfRange(mesg, 8, mesg.length),
                shortNonce("CurveCP-server-M", ByteBuffer.wrap(mesg).getLong()));
        int length = ByteBuffer.wrap(plain).getShort() & 0xffff;
        return Arrays.copyOfRange(plain, 2, 2 + length);
    }

    private void writePacket(String command, byte[] data) throws IOException {
        sendRaw(frame(command, data));
    }

    static byte[] frame(String command, byte[] data) {
        return ByteBuffer.allocate(10 + data.length).putShort((short) (8 + data.length)).putInt(MAGIC)
                .put(command.getBytes(StandardCharsets.US_ASCII)).put(data).array();
    }

    private byte[] readPacket(String expected) throws IOException {
        int length = in.readUnsignedShort();
        lastFrameLength = length;
        var frame = new byte[length];
        in.readFully(frame);
        var command = new String(frame, 4, 4, StandardCharsets.US_ASCII);
        if (ByteBuffer.wrap(frame).getInt() != MAGIC || !expected.equals(command)) {
            throw new IOException("expected " + expected + " but got " + command);
        }
        return Arrays.copyOfRange(frame, 8, frame.length);
    }

    private static byte[] seal(byte[] plain, byte[] nonce, byte[] theirKey, byte[] ourKey) throws IOException {
        var buffer = new byte[32 + plain.length];
        System.arraycopy(plain, 0, buffer, 32, plain.length);
        if (curve25519xsalsa20poly1305.crypto_box(buffer, buffer, nonce, theirKey, ourKey) != 0) {
            throw new IOException("seal failed");
        }
        return Arrays.copyOfRange(buffer, 16, buffer.length);
    }

    private static byte[] open(byte[] box, byte[] nonce, byte[] theirKey, byte[] ourKey) throws IOException {
        var buffer = new byte[16 + box.length];
        System.arraycopy(box, 0, buffer, 16, box.length);
        if (curve25519xsalsa20poly1305.crypto_box_open(buffer, buffer, nonce, theirKey, ourKey) != 0) {
            throw new IOException("open failed");
        }
        return Arrays.copyOfRange(buffer, 32, buffer.length);
    }

    private byte[] sealAfterNm(byte[] plain, byte[] nonce) throws IOException {
        var buffer = new byte[32 + plain.length];
        System.arraycopy(plain, 0, buffer, 32, plain.length);
        if (curve25519xsalsa20poly1305.crypto_box_afternm(buffer, buffer, nonce, sharedKey) != 0) {
            throw new IOException("seal failed");
        }
        return Arrays.copyOfRange(buffer, 16, buffer.length);
    }

    private byte[] openAfterNm(byte[] box, byte[] nonce) throws IOException {
        var buffer = new byte[16 + box.length];
        System.arraycopy(box, 0, buffer, 16, box.length);
        if (curve25519xsalsa20poly1305.crypto_box_open_afternm(buffer, buffer, nonce, sharedKey) != 0) {
            throw new IOException("open failed");
        }
        return Arrays.copyOfRange(buffer, 32, buffer.length);
    }

    private static byte[] shortNonce(String prefix, long value) {
        return ByteBuffer.allocate(24).put(prefix.getBytes(StandardCharsets.US_ASCII)).putLong(value).array();
    }

    private static byte[] longNonce(String prefix, byte[] value) {
        return ByteBuffer.allocate(24).put(prefix.getBytes(StandardCharsets.US_ASCII)).put(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (var part : parts) {
            size += part.length;
        }
        var buffer = ByteBuffer.allocate(size);
        for (var part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
