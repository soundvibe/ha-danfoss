package net.soundvibe.hasio.danfoss.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Reassembles the house configuration sent by a phone over "dominion-configuration-1.0".
 * <p>
 * The configuration arrives either as a single packet with JSON, or in chunked mode: the first
 * packet starts with a header (integer 0, then the full length as a little-endian integer), and
 * the data continues in the following packets.
 */
final class ConfigurationReader {

    /** Real configurations are a few kilobytes of JSON; anything near this limit is not a configuration. */
    static final int MAX_CONFIGURATION_SIZE = 1 << 20;
    private static final int HEADER_SIZE = 8;

    @FunctionalInterface
    interface PacketSource {
        /**
         * @return the next packet, or null when the peer has closed the connection
         */
        InputStream next() throws IOException, InterruptedException, ExecutionException, TimeoutException;
    }

    private ConfigurationReader() {
    }

    static byte[] read(PacketSource packets)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        byte[] data = null;
        int offset = 0;
        do {
            var packet = packets.next();
            if (packet == null) {
                throw new ProtocolException(data == null
                        ? "No configuration received"
                        : "Configuration truncated: received " + offset + " of " + data.length + " bytes");
            }
            var chunk = packet.readAllBytes();
            int from = 0;
            if (data == null) {
                var header = ByteBuffer.wrap(chunk);
                // The header has integer 0 in the beginning so that it's easily distinguished from JSON plaintext
                if (chunk.length > HEADER_SIZE && header.getInt() == 0) {
                    int declared = header.order(ByteOrder.LITTLE_ENDIAN).getInt();
                    if (declared <= 0 || declared > MAX_CONFIGURATION_SIZE) {
                        throw new ProtocolException("Invalid configuration size " + declared);
                    }
                    data = new byte[declared];
                    from = HEADER_SIZE;
                } else {
                    // No header: the whole configuration is in this packet
                    if (chunk.length == 0) {
                        throw new ProtocolException("No configuration received");
                    }
                    if (chunk.length > MAX_CONFIGURATION_SIZE) {
                        throw new ProtocolException("Invalid configuration size " + chunk.length);
                    }
                    return chunk;
                }
            }
            int length = chunk.length - from;
            if (length > data.length - offset) {
                throw new ProtocolException("Configuration chunk of " + length + " bytes exceeds the "
                        + (data.length - offset) + " bytes still expected");
            }
            System.arraycopy(chunk, from, data, offset, length);
            offset += length;
        } while (offset < data.length);
        return data;
    }
}
