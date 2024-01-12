package net.soundvibe.hasio.danfoss.protocol.config;

public record AppConfig(
        byte[] privateKey,
        String userName,
        String peerId) {}

