package net.soundvibe.hasio.danfoss.protocol;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConfigurationReaderTest {

    private static final byte[] JSON = "{\"houseName\":\"MyHouse\",\"housePeerId\":\"abcdef\",\"houseEditUsers\":false}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void should_read_a_configuration_sent_in_a_single_packet() throws Exception {
        var packets = packets(JSON, "must not be read".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(JSON, ConfigurationReader.read(packets::poll));
        assertEquals(1, packets.size(), "nothing is read past the configuration");
    }

    @Test
    void should_reassemble_a_chunked_configuration() throws Exception {
        var first = concat(header(JSON.length), Arrays.copyOfRange(JSON, 0, 20));
        var second = Arrays.copyOfRange(JSON, 20, 50);
        var third = Arrays.copyOfRange(JSON, 50, JSON.length);
        var packets = packets(first, second, third, "must not be read".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(JSON, ConfigurationReader.read(packets::poll));
        assertEquals(1, packets.size(), "nothing is read past the declared size");
    }

    @Test
    void should_reject_a_declared_size_above_the_limit() {
        var packets = packets(concat(header(ConfigurationReader.MAX_CONFIGURATION_SIZE + 1), new byte[]{'{'}));

        var error = assertThrows(ProtocolException.class, () -> ConfigurationReader.read(packets::poll));
        assertEquals("Invalid configuration size " + (ConfigurationReader.MAX_CONFIGURATION_SIZE + 1), error.getMessage());
    }

    @Test
    void should_reject_a_declared_size_that_is_not_positive() {
        for (int declared : List.of(0, -1, Integer.MIN_VALUE)) {
            var packets = packets(concat(header(declared), new byte[]{'{'}));

            assertThrows(ProtocolException.class, () -> ConfigurationReader.read(packets::poll), "declared " + declared);
        }
    }

    @Test
    void should_reject_a_chunk_longer_than_the_bytes_still_expected() {
        var packets = packets(concat(header(10), new byte[6]), new byte[5]);

        var error = assertThrows(ProtocolException.class, () -> ConfigurationReader.read(packets::poll));
        assertEquals("Configuration chunk of 5 bytes exceeds the 4 bytes still expected", error.getMessage());
    }

    @Test
    void should_reject_a_configuration_cut_short_by_the_end_of_the_connection() {
        var packets = packets(concat(header(JSON.length), Arrays.copyOfRange(JSON, 0, 20)));

        var error = assertThrows(ProtocolException.class, () -> ConfigurationReader.read(packets::poll));
        assertEquals("Configuration truncated: received 20 of " + JSON.length + " bytes", error.getMessage());
    }

    @Test
    void should_reject_a_connection_that_delivers_nothing() {
        var closed = packets();
        var empty = packets(new byte[0]);

        assertEquals("No configuration received",
                assertThrows(ProtocolException.class, () -> ConfigurationReader.read(closed::poll)).getMessage());
        assertEquals("No configuration received",
                assertThrows(ProtocolException.class, () -> ConfigurationReader.read(empty::poll)).getMessage());
    }

    private static ArrayDeque<ByteArrayInputStream> packets(byte[]... packets) {
        var queue = new ArrayDeque<ByteArrayInputStream>();
        for (var packet : packets) {
            queue.add(new ByteArrayInputStream(packet));
        }
        return queue;
    }

    /** Chunked mode header: integer 0, then the full length as a little-endian integer. */
    private static byte[] header(int length) {
        return ByteBuffer.allocate(8).putInt(0).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
