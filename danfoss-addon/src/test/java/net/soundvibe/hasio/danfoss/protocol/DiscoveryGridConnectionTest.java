package net.soundvibe.hasio.danfoss.protocol;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import io.github.sonic_amiga.opensdg.java.SDG;
import io.github.sonic_amiga.opensdg.protocol.Control;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCall;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.IncomingCallReply;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.PeerInfo;
import io.github.sonic_amiga.opensdg.protocol.generated.ControlProtocol.Pong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DiscoveryGridConnectionTest {

    private ScheduledExecutorService scheduler;
    private RecordingConnection sut;

    /** Captures what would be sent to the grid instead of writing to a socket. */
    private static class RecordingConnection extends DiscoveryGridConnection {
        final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());

        RecordingConnection(ScheduledExecutorService scheduler) {
            super(SDG.createPrivateKey(), scheduler);
        }

        @Override
        protected void sendControl(byte type, byte[] body) {
            var message = new byte[body.length + 1];
            message[0] = type;
            System.arraycopy(body, 0, message, 1, body.length);
            sent.add(message);
        }

        void deliver(byte[] message) throws Exception {
            onDataPacket(new ByteArrayInputStream(message));
        }

        List<Integer> refusedCalls() throws IOException {
            var ids = new ArrayList<Integer>();
            synchronized (sent) {
                for (var message : sent) {
                    if (message[0] == Control.MSG_INCOMING_CALL_REPLY) {
                        var reply = IncomingCallReply.parseFrom(Arrays.copyOfRange(message, 1, message.length));
                        if (reply.getResult() == 0) {
                            ids.add(reply.getId());
                        }
                    }
                }
            }
            return ids;
        }
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        sut = new RecordingConnection(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void should_queue_an_incoming_call_until_it_is_polled() throws Exception {
        sut.deliver(incomingCall(7));

        var call = sut.pollIncomingCall(1, TimeUnit.SECONDS);

        assertNotNull(call);
        assertEquals(7, call.getId());
        assertEquals("dominion-configuration-1.0", call.getProtocol());
        assertEquals("caller", call.getPeer().getPeerId());
        assertTrue(sut.sent.isEmpty(), "a queued call is answered by whoever polls it");
    }

    @Test
    void should_reply_with_the_call_id_and_the_decision() throws Exception {
        sut.deliver(incomingCall(7));
        var call = sut.pollIncomingCall(1, TimeUnit.SECONDS);

        sut.replyToIncomingCall(call, true);

        var message = sut.sent.get(0);
        var reply = IncomingCallReply.parseFrom(Arrays.copyOfRange(message, 1, message.length));
        assertEquals(Control.MSG_INCOMING_CALL_REPLY, message[0]);
        assertEquals(7, reply.getId());
        assertEquals(1, reply.getResult());
    }

    @Test
    void should_refuse_queued_and_later_calls_after_stopping() throws Exception {
        sut.deliver(incomingCall(1));
        sut.deliver(incomingCall(2));

        sut.stopAcceptingCalls();
        sut.deliver(incomingCall(3));

        assertEquals(List.of(1, 2, 3), sut.refusedCalls());
        assertNull(sut.pollIncomingCall(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void should_refuse_calls_beyond_the_queue_capacity() throws Exception {
        int capacity = DiscoveryGridConnection.MAX_PENDING_CALLS;
        for (int id = 1; id <= capacity + 2; id++) {
            sut.deliver(incomingCall(id));
        }

        assertEquals(List.of(capacity + 1, capacity + 2), sut.refusedCalls());
        for (int id = 1; id <= capacity; id++) {
            assertEquals(id, sut.pollIncomingCall(10, TimeUnit.MILLISECONDS).getId());
        }
        assertNull(sut.pollIncomingCall(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void should_refuse_a_call_that_is_being_queued_while_stopping() throws Exception {
        var insideQueueing = new CountDownLatch(1);
        var stopper = new AtomicReference<Thread>();
        var connection = new RecordingConnection(scheduler) {
            @Override
            boolean enqueue(IncomingCall call) {
                // The receiver has decided to accept the call but has not inserted it yet.
                // Hold it here until stopAcceptingCalls() is blocked behind us.
                insideQueueing.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (stopper.get() == null || stopper.get().getState() != Thread.State.BLOCKED) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("stopAcceptingCalls() did not wait for the pending insertion");
                    }
                    Thread.onSpinWait();
                }
                return super.enqueue(call);
            }
        };
        try (var threads = Executors.newFixedThreadPool(2)) {
            var delivering = threads.submit(() -> {
                connection.deliver(incomingCall(5));
                return null;
            });
            var stopping = threads.submit(() -> {
                stopper.set(Thread.currentThread());
                insideQueueing.await();
                connection.stopAcceptingCalls();
                return null;
            });
            delivering.get(10, TimeUnit.SECONDS);
            stopping.get(10, TimeUnit.SECONDS);
        }

        assertEquals(List.of(5), connection.refusedCalls(), "the call queued during shutdown is refused, not left behind");
        assertNull(connection.pollIncomingCall(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void should_register_pairings_with_a_sequence_number_and_raw_peer_ids() throws Exception {
        var first = SDG.calcPublicKey(SDG.createPrivateKey());
        var second = SDG.calcPublicKey(SDG.createPrivateKey());

        sut.registerPairings(List.of(first, second));
        sut.registerPairings(List.of(first));

        assertEquals(DiscoveryGridConnection.MSG_PAIRINGS, sut.sent.get(0)[0]);
        var body = CodedInputStream.newInstance(sut.sent.get(0), 1, sut.sent.get(0).length - 1);
        assertEquals(1 << 3, body.readTag());
        assertEquals(0, body.readUInt32());
        assertEquals((2 << 3) | 2, body.readTag());
        assertArrayEquals(first, body.readByteArray());
        assertEquals((2 << 3) | 2, body.readTag());
        assertArrayEquals(second, body.readByteArray());
        assertTrue(body.isAtEnd());

        var next = CodedInputStream.newInstance(sut.sent.get(1), 1, sut.sent.get(1).length - 1);
        next.readTag();
        assertEquals(1, next.readUInt32(), "the sequence number grows with every list sent");
    }

    @Test
    void should_ignore_the_confirmation_of_pairings() throws Exception {
        sut.deliver(new byte[]{DiscoveryGridConnection.MSG_PAIRINGS_REPLY, 0x08, 0x00});

        assertTrue(sut.sent.isEmpty());
        assertNull(sut.pollIncomingCall(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void should_leave_other_grid_messages_to_opensdg() throws Exception {
        var pong = Pong.newBuilder().setSeq(99).build().toByteArray();
        var message = new byte[pong.length + 1];
        message[0] = Control.MSG_PONG;
        System.arraycopy(pong, 0, message, 1, pong.length);

        assertDoesNotThrow(() -> sut.deliver(message));
        assertTrue(sut.sent.isEmpty());
    }

    private static byte[] incomingCall(int id) {
        var body = IncomingCall.newBuilder()
                .setId(id)
                .setPeer(PeerInfo.newBuilder()
                        .setPeerId("caller")
                        .setServer(PeerInfo.Endpoint.newBuilder().setHost("127.0.0.1").setPort(443))
                        .setTunnelId(ByteString.copyFromUtf8("tunnel")))
                .setProtocol("dominion-configuration-1.0")
                .build()
                .toByteArray();
        var message = new byte[body.length + 1];
        message[0] = Control.MSG_INCOMING_CALL;
        System.arraycopy(body, 0, message, 1, body.length);
        return message;
    }
}
