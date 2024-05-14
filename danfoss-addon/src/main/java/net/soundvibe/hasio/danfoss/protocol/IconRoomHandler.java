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
        private boolean heatingCoolingOn;
        private boolean coolingEnabled;

        public final int roomNumber;

        private Room(int roomNumber) {
            this.roomNumber = roomNumber;
            this.roomName = "";
        }

        @Override
        public String toString() {
            return STR."Room{roomName='\{roomName}\{'\''}, temperature=\{temperature}, temperatureFloor=\{temperatureFloor}, temperatureFloorMin=\{temperatureFloorMin}, temperatureFloorMax=\{temperatureFloorMax}, setPointHigh=\{setPointHigh}, setPointLow=\{setPointLow}, setPointHome=\{setPointHome}, setPointAway=\{setPointAway}, setPointSleep=\{setPointSleep}, batteryPercent=\{batteryPercent}, roomMode=\{roomMode}, manualControl=\{manualControl}, heatingState=\{heatingState}, roomNumber=\{roomNumber}\{'}'}";
        }
    }

    private final Room room;
    private final SDGPeerConnector connector;
    public final int roomNumber;
    private final ReadWriteLock lock;

    public IconRoomHandler(SDGPeerConnector connector, int roomNumber) {
        this.room = new Room(roomNumber);
        this.lock = new ReentrantReadWriteLock(true);
        this.connector = connector;
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
                room.heatingCoolingOn = pkt.getBoolean();
                room.heatingState = HeatingState.from(room.heatingCoolingOn, room.coolingEnabled);
                lock.writeLock().unlock();
                break;
            case ROOM_COOLINGENABLED:
                lock.writeLock().lock();
                room.coolingEnabled = pkt.getBoolean();
                room.heatingState = HeatingState.from(room.heatingCoolingOn, room.coolingEnabled);
                lock.writeLock().unlock();
        }
    }

    @Override
    public void ping() {
        logger.debug("room {} ping", roomNumber);
    }

    public void refresh() {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_ROOMTEMPERATURE));
    }

    public void setHomeTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTATHOME, newTemperature));
        lock.writeLock().lock();
        room.setPointHome = newTemperature;
        lock.writeLock().unlock();
    }

    public void setAwayTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTAWAY, newTemperature));
        lock.writeLock().lock();
        room.setPointAway = newTemperature;
        lock.writeLock().unlock();
    }

    public void setSleepTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTASLEEP, newTemperature));
        lock.writeLock().lock();
        room.setPointSleep = newTemperature;
        lock.writeLock().unlock();
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
