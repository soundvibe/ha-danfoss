package net.soundvibe.hasio.ha.model;

import java.util.Map;

public record State(String state, Map<String, String> attributes) {}
