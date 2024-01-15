package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.Json;
import org.junit.jupiter.api.Test;

class IconRoomTest {

    @Test
    void testStateJson() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 99);
        var state = sut.toState();

        System.out.println(Json.toJsonString(state));
    }
}