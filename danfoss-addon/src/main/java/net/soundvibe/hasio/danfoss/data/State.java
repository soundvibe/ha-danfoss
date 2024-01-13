package net.soundvibe.hasio.danfoss.data;

import java.util.Map;

public record State(String state, Map<String, String> attributes) {}
