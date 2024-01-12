package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.soundvibe.hasio.danfoss.protocol.config.DanfossBindingConstants.ICON_MAX_ROOMS;
import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgClass.*;
import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgCode.*;


public class IconMasterHandler implements PacketHandler {

    private static final Logger logger = LoggerFactory.getLogger(IconMasterHandler.class);

    private final IconRoomHandler[] rooms = new IconRoomHandler[ICON_MAX_ROOMS];
    private final Map<String, IconRoomHandler> roomsByName = new ConcurrentHashMap<>(128);

    private final AtomicReference<Double> vacationSetPoint = new AtomicReference<>();
    private final AtomicReference<Double> pauseSetPoint = new AtomicReference<>();
    private final AtomicReference<String> hardwareRevision = new AtomicReference<>();
    private final AtomicReference<String> softwareRevision = new AtomicReference<>();
    private final AtomicReference<String> serialNumber = new AtomicReference<>();
    private final AtomicInteger softwareBuildRevision = new AtomicInteger();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicReference<Instant> productionDate = new AtomicReference<>();
    private final byte[] privateKey;
    private final ScheduledExecutorService executorService;
    private final SDGPeerConnector connector;

    public IconMasterHandler(byte[] privateKey, ScheduledExecutorService executorService) {
        this.privateKey = privateKey;
        this.executorService = executorService;
        this.connector = new SDGPeerConnector(this, this.privateKey, this.executorService);
        for (int i = 0; i < ICON_MAX_ROOMS; i++) {
            this.rooms[i] = new IconRoomHandler(this.connector, i);
        }
    }

    public void scanRooms(String housePeerId) {
        connector.initialize(housePeerId);
        // Request names for all the rooms
        for (int msgClass = ROOM_FIRST; msgClass <= ROOM_LAST; msgClass++) {
            connector.SendPacket(new Dominion.Packet(msgClass, ROOMNAME));
        }
    }

    public List<IconRoom> listRooms() {
        return this.roomsByName.values().stream()
                .map(IconRoomHandler::toIconRoom)
                .toList();
    }

    @Override
    public void handlePacket(Dominion.Packet pkt) {
        int msgClass = pkt.getMsgClass();

        if (msgClass >= ROOM_FIRST && msgClass <= ROOM_LAST) {
            var room = rooms[msgClass - ROOM_FIRST];
            room.handlePacket(pkt);
            var name = room.nameOrEmpty();
            if (!name.isEmpty()) {
                this.roomsByName.putIfAbsent(name, room);
            }
        } else {
            switch (pkt.getMsgCode()) {
                case VACATION_SETPOINT:
                    this.vacationSetPoint.set(pkt.getDecimal());
                    //reportTemperature(CHANNEL_SETPOINT_AWAY, pkt.getDecimal());
                    break;
                case PAUSE_SETPOINT:
                    this.pauseSetPoint.set(pkt.getDecimal());
                    //reportTemperature(CHANNEL_SETPOINT_ANTIFREEZE, pkt.getDecimal());
                    break;
                case GLOBAL_HARDWAREREVISION:
                    this.hardwareRevision.set(pkt.getVersion().toString());
                    //updateProperty(Thing.PROPERTY_HARDWARE_VERSION, pkt.getVersion().toString());
                    break;
                case GLOBAL_SOFTWAREREVISION:
                    this.softwareRevision.set(pkt.getVersion().toString());
                    //firmwareVer = pkt.getVersion();
                    //reportFirmware();
                    break;
                case GLOBAL_SOFTWAREBUILDREVISION:
                    this.softwareBuildRevision.set(Short.toUnsignedInt(pkt.getShort()));
                    //reportFirmware();
                    break;
                case GLOBAL_SERIALNUMBER:
                    this.serialNumber.set(String.valueOf(pkt.getInt()));
                    //updateProperty(Thing.PROPERTY_SERIAL_NUMBER, String.valueOf(pkt.getInt()));
                    break;
                case GLOBAL_PRODUCTIONDATE:
                    this.productionDate.set(pkt.getDate(0).toInstant());
                    //updateProperty("productionDate", DateFormat.getDateTimeInstance().format(pkt.getDate(0)));
                    break;
                case MDG_CONNECTION_COUNT:
                    this.connectionCount.set(pkt.getByte());
                    //updateProperty("connectionCount", String.valueOf(pkt.getByte()));
                    break;
            }
        }
    }

    @Override
    public void ping() {
        this.connector.SendPacket(new Dominion.Packet(ALL_ROOMS, VACATION_SETPOINT));
    }
}
