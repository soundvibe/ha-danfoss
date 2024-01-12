package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.protocol.config.Dominion;

public interface PacketHandler {

    void handlePacket(Dominion.Packet pkt);

    void ping();
}
