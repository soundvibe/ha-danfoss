package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.data.HeatingState;
import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.data.RoomMode;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import net.soundvibe.hasio.danfoss.protocol.config.Icon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgClass.ROOM_FIRST;
import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgCode.*;

public class IconRoomHandler implements PacketHandler {

    private static final Logger logger = LoggerFactory.getLogger(IconRoomHandler.class);

    private static class Room {
        private String roomName;
        private double temperature;
        private double temperatureFloor;
        private double temperatureFloorMin;
        private double temperatureFloorMax;
        private double setPointHigh;
        private double setPointLow;
        private double setPointHome;
        private double setPointAway;
        private double setPointSleep;
        private short batteryPercent;
        private RoomMode roomMode;
        private boolean manualControl;
        private HeatingState heatingState;

        public final int roomNumber;

        private Room(int roomNumber) {
            this.roomNumber = roomNumber;
            this.roomName = "";
            this.temperature = Double.NaN;
            this.setPointHome = Double.NaN;
            this.setPointAway = Double.NaN;
            this.setPointSleep = Double.NaN;
            this.setPointLow = Double.NaN;
            this.setPointHigh = Double.NaN;
        }

        @Override
        public String toString() {
            return STR."Room{roomName='\{roomName}\{'\''}, temperature=\{temperature}, temperatureFloor=\{temperatureFloor}, temperatureFloorMin=\{temperatureFloorMin}, temperatureFloorMax=\{temperatureFloorMax}, setPointHigh=\{setPointHigh}, setPointLow=\{setPointLow}, setPointHome=\{setPointHome}, setPointAway=\{setPointAway}, setPointSleep=\{setPointSleep}, batteryPercent=\{batteryPercent}, roomMode=\{roomMode}, manualControl=\{manualControl}, heatingState=\{heatingState}, roomNumber=\{roomNumber}\{'}'}";
        }
    }

    private final Room room;
    private final Consumer<Dominion.Packet> sendPacket;
    public final int roomNumber;
    private final ReadWriteLock lock;

    public IconRoomHandler(SDGPeerConnector connector, int roomNumber) {
        this(connector::SendPacket, roomNumber);
    }

    public IconRoomHandler(Consumer<Dominion.Packet> sendPacket, int roomNumber) {
        this.room = new Room(roomNumber);
        this.lock = new ReentrantReadWriteLock(true);
        this.sendPacket = sendPacket;
        this.roomNumber = roomNumber;
    }

    public String nameOrEmpty() {
        lock.readLock().lock();
        try {
            return room.roomName;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void handlePacket(Dominion.Packet pkt) {
        switch (pkt.getMsgCode()) {
            case ROOM_FLOORTEMPERATURE:
                lock.writeLock().lock();
                room.temperatureFloor = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_ROOMTEMPERATURE:
                lock.writeLock().lock();
                room.temperature = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_SETPOINTMAXIMUM:
                lock.writeLock().lock();
                room.setPointHigh = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_SETPOINTMINIMUM:
                lock.writeLock().lock();
                room.setPointLow = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_SETPOINTATHOME:
                lock.writeLock().lock();
                room.setPointHome = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_SETPOINTASLEEP:
                lock.writeLock().lock();
                room.setPointSleep = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_SETPOINTAWAY:
                lock.writeLock().lock();
                room.setPointAway = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_FLOORTEMPERATUREMINIMUM:
                lock.writeLock().lock();
                room.temperatureFloorMin = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_FLOORTEMPERATUREMAXIMUM:
                lock.writeLock().lock();
                room.temperatureFloorMax = pkt.getDecimal();
                lock.writeLock().unlock();
                break;
            case ROOM_BATTERYINDICATIONPERCENT:
                lock.writeLock().lock();
                room.batteryPercent = pkt.getByte();
                lock.writeLock().unlock();
                break;
            case ROOM_ROOMMODE:
                setRoomMode(pkt.getByte());
                break;
            case ROOM_ROOMCONTROL:
                lock.writeLock().lock();
                room.manualControl = pkt.getByte() == Icon.RoomControl.Manual;
                lock.writeLock().unlock();
                break;
            case ROOMNAME:
                var roomName = pkt.getString();
                if (roomName != null && !roomName.isEmpty()) {
                    lock.writeLock().lock();
                    room.roomName = roomName;
                    logger.debug("room={}", room);
                    lock.writeLock().unlock();
                }
                break;
            case ROOM_HEATINGCOOLINGSTATE:
                lock.writeLock().lock();
                room.heatingState = switch (pkt.getByte()) {
                    case 1 -> HeatingState.HEAT;
                    case 2 -> HeatingState.COOL;
                    default -> HeatingState.OFF;
                };
                lock.writeLock().unlock();
                break;
            case ROOM_HEATINGCOOLINGCONFIGURATION:
                logger.debug("room heating/cooling configuration={}", pkt.getByte());
                break;
        }
    }

    @Override
    public void ping() {
        logger.debug("room {} ping", roomNumber);
    }

    public void refresh() {
        sendPacket.accept(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_ROOMTEMPERATURE));
    }

    public void setHomeTemperature(double newTemperature) {
        setTemperature(ROOM_SETPOINTATHOME, newTemperature);
    }

    public void setAwayTemperature(double newTemperature) {
        setTemperature(ROOM_SETPOINTAWAY, newTemperature);
    }

    public void setSleepTemperature(double newTemperature) {
        setTemperature(ROOM_SETPOINTASLEEP, newTemperature);
    }

    private void setTemperature(int code, double temperature) {
        var snapshot = toIconRoom();
        if (!Double.isFinite(temperature) || !Double.isFinite(snapshot.temperatureLow())
                || !Double.isFinite(snapshot.temperatureHigh())
                || temperature < snapshot.temperatureLow() || temperature > snapshot.temperatureHigh()) {
            throw new IllegalArgumentException("Temperature outside reported room limits");
        }
        sendPacket.accept(new Dominion.Packet(ROOM_FIRST + roomNumber, code, temperature));
        // Only packets returned by the controller may update the reported setpoint.
        sendPacket.accept(new Dominion.Packet(ROOM_FIRST + roomNumber, code));
    }

    public void setTargetTemperature(double temperature) {
        var preset = toIconRoom().roomMode();
        if (preset == null) throw new IllegalStateException("Room preset not received yet");
        switch (preset) {
            case HOME -> setHomeTemperature(temperature);
            case AWAY -> setAwayTemperature(temperature);
            case SLEEP -> setSleepTemperature(temperature);
            case FATAL -> throw new IllegalStateException("Room is in fatal mode");
        }
    }

    public void setPreset(RoomMode preset) {
        byte value = switch (preset) {
            case HOME -> Icon.RoomMode.AtHome;
            case AWAY -> Icon.RoomMode.Away;
            case SLEEP -> Icon.RoomMode.Asleep;
            case FATAL -> throw new IllegalArgumentException("Fatal is not a selectable preset");
        };
        sendPacket.accept(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_ROOMMODE, value));
        sendPacket.accept(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_ROOMMODE));
    }

    private void setRoomMode(byte mode) {
        lock.writeLock().lock();
        try {
            switch (mode) {
                case Icon.RoomMode.AtHome:
                    room.roomMode = RoomMode.HOME;
                    break;
                case Icon.RoomMode.Away:
                    room.roomMode = RoomMode.AWAY;
                    break;
                case Icon.RoomMode.Asleep:
                    room.roomMode = RoomMode.SLEEP;
                    break;
                case Icon.RoomMode.Fatal:
                    room.roomMode = RoomMode.FATAL;
                    break;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public IconRoom toIconRoom() {
        lock.readLock().lock();
        try {
            return new IconRoom(room.roomName, room.roomNumber, room.temperature,
                    room.setPointHome, room.setPointAway, room.setPointSleep, room.setPointHigh, room.setPointLow,
                    room.batteryPercent, room.heatingState, room.roomMode);
        } finally {
            lock.readLock().unlock();
        }
    }
}
