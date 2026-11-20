package net.soundvibe.hasio.danfoss.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.github.sonic_amiga.opensdg.java.GridConnection;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCall;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCallReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Grid connection used while discovering a house (receiving it from a phone).
 * <p>
 * It adds two parts of the Danfoss (Trifork MDG) grid protocol that opensdg-java does not implement,
 * but which are needed when the house is shared from the Danfoss Icon iOS app:
 * <ul>
 *   <li>message 22: the list of peers we are paired with. The grid routes an incoming call to us only
 *   when the caller is on this list. The official mdglib sends it after every pairing change and the
 *   grid confirms it with message 21.</li>
 *   <li>messages 12/13 (incoming call and reply): right after pairing, the iOS app calls the receiving
 *   side on "dominion-configuration-1.0" instead of waiting for a call.</li>
 * </ul>
 */
public class DiscoveryGridConnection extends GridConnection {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryGridConnection.class);

    static final byte MSG_INCOMING_CALL = 12;
    static final byte MSG_INCOMING_CALL_REPLY = 13;
    static final byte MSG_PAIRINGS_REPLY = 21;
    static final byte MSG_PAIRINGS = 22;

    private final BlockingQueue<IncomingCall> incomingCalls = new LinkedBlockingQueue<>();
    private volatile boolean acceptingCalls = true;
    private int pairingsSequence;

    public DiscoveryGridConnection(byte[] key, ScheduledExecutorService scheduler) {
        super(key, scheduler);
    }

    /**
     * Tells the grid which peers may call us. Like the official library, the whole list is sent each time.
     */
    public synchronized void registerPairings(List<byte[]> peerIds)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(MSG_PAIRINGS);
        var out = CodedOutputStream.newInstance(bytes);
        out.writeUInt32(1, pairingsSequence++);
        for (var peerId : peerIds) {
            out.writeBytes(2, ByteString.copyFrom(peerId));
        }
        out.flush();
        tunnel.sendData(bytes.toByteArray());
        logger.debug("Registered {} pairing(s) with the grid", peerIds.size());
    }

    /**
     * Waits for the next incoming call. The caller must answer it with {@link #replyToIncomingCall}.
     */
    public IncomingCall pollIncomingCall(long timeout, TimeUnit unit) throws InterruptedException {
        return incomingCalls.poll(timeout, unit);
    }

    public void replyToIncomingCall(IncomingCall call, boolean accept)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        var reply = IncomingCallReply.newBuilder()
                .setId(call.getId())
                .setResult(accept ? 1 : 0)
                .build()
                .toByteArray();
        var message = new byte[reply.length + 1];
        message[0] = MSG_INCOMING_CALL_REPLY;
        System.arraycopy(reply, 0, message, 1, reply.length);
        tunnel.sendData(message);
    }

    /**
     * Refuses all queued and future incoming calls, so that callers get an answer instead of a timeout.
     */
    public void stopAcceptingCalls() {
        acceptingCalls = false;
        IncomingCall call;
        while ((call = incomingCalls.poll()) != null) {
            refuseQuietly(call);
        }
    }

    @Override
    protected void onDataPacket(InputStream data)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        var in = data.markSupported() ? data : new BufferedInputStream(data);
        in.mark(Integer.MAX_VALUE);
        int msgType = in.read();
        if (msgType == MSG_INCOMING_CALL) {
            var call = IncomingCall.parseFrom(in);
            logger.info("Incoming call from peer {} (protocol {})", call.getPeer().getPeerId(), call.getProtocol());
            if (acceptingCalls) {
                incomingCalls.offer(call);
            } else {
                refuseQuietly(call);
            }
        } else if (msgType == MSG_PAIRINGS_REPLY) {
            logger.debug("Grid confirmed the list of pairings");
        } else {
            in.reset();
            super.onDataPacket(in);
        }
    }

    private void refuseQuietly(IncomingCall call) {
        try {
            replyToIncomingCall(call, false);
        } catch (Exception e) {
            logger.debug("Failed to refuse incoming call {}: {}", call.getId(), e.toString());
        }
    }
}
