package net.soundvibe.hasio.danfoss.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IconRoomTest {

    private static final Gson GSON = new Gson();

    @Test
    void testStateJson() {
        var sut = new IconRoom("Living Room", 1, 22.3, 23.0, 21.0, 99);
        var state = sut.toState();

        System.out.println(GSON.toJson(state));
    }
}