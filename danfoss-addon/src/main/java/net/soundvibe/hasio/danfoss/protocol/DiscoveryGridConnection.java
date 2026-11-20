package net.soundvibe.hasio.danfoss.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.github.sonic_amiga.opensdg.java.GridConnection;
import io.github.sonic_amiga.opensdg.protocol.Control;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCall;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCallReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
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

    static final byte MSG_PAIRINGS_REPLY = 21;
    static final byte MSG_PAIRINGS = 22;
    /** Discovery expects a single call from the sender; anything beyond a few candidates is refused. */
    static final int MAX_PENDING_CALLS = 4;

    private final Object callsLock = new Object();
    private final BlockingQueue<IncomingCall> incomingCalls = new ArrayBlockingQueue<>(MAX_PENDING_CALLS);
    private boolean acceptingCalls = true;      // guarded by callsLock
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
        var out = CodedOutputStream.newInstance(bytes);
        out.writeUInt32(1, pairingsSequence++);
        for (var peerId : peerIds) {
            out.writeBytes(2, ByteString.copyFrom(peerId));
        }
        out.flush();
        sendControl(MSG_PAIRINGS, bytes.toByteArray());
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
        sendControl(Control.MSG_INCOMING_CALL_REPLY, reply);
    }

    /**
     * Refuses all queued and future incoming calls, so that callers get an answer instead of a timeout.
     * Once this method returns, no call can be left waiting in the queue.
     */
    public void stopAcceptingCalls() {
        var pending = new ArrayList<IncomingCall>();
        synchronized (callsLock) {
            acceptingCalls = false;
            incomingCalls.drainTo(pending);
        }
        pending.forEach(this::refuseQuietly);
    }

    @Override
    protected void onDataPacket(InputStream data)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        var in = data.markSupported() ? data : new BufferedInputStream(data);
        in.mark(Integer.MAX_VALUE);
        int msgType = in.read();
        if (msgType == Control.MSG_INCOMING_CALL) {
            var call = IncomingCall.parseFrom(in);
            logger.info("Incoming call from peer {} (protocol {})", call.getPeer().getPeerId(), call.getProtocol());
            boolean queued;
            synchronized (callsLock) {
                // the decision and the insertion are one step, so stopAcceptingCalls() cannot slip in between
                queued = acceptingCalls && enqueue(call);
            }
            if (!queued) {
                refuseQuietly(call);
            }
        } else if (msgType == MSG_PAIRINGS_REPLY) {
            logger.debug("Grid confirmed the list of pairings");
        } else {
            in.reset();
            super.onDataPacket(in);
        }
    }

    /**
     * Adds a call to the bounded queue; false when the queue is full. Always called with the calls
     * lock held. Separate method so that a test can pause exactly between the decision and the insertion.
     */
    boolean enqueue(IncomingCall call) {
        return incomingCalls.offer(call);
    }

    /**
     * Sends one control message (type byte followed by the protobuf body) to the grid.
     */
    protected void sendControl(byte type, byte[] body)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        var message = new byte[body.length + 1];
        message[0] = type;
        System.arraycopy(body, 0, message, 1, body.length);
        tunnel.sendData(message);
    }

    private void refuseQuietly(IncomingCall call) {
        try {
            replyToIncomingCall(call, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.debug("Failed to refuse incoming call {}: {}", call.getId(), e.toString());
        }
    }
}
