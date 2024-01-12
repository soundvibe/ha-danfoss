package net.soundvibe.hasio.danfoss.protocol;

import net.soundvibe.hasio.danfoss.data.IconRoom;
import net.soundvibe.hasio.danfoss.protocol.config.Dominion;
import net.soundvibe.hasio.danfoss.protocol.config.Icon;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgClass.ROOM_FIRST;
import static net.soundvibe.hasio.danfoss.protocol.config.Icon.MsgCode.*;

public class IconRoomHandler implements PacketHandler {
    private final AtomicReference<String> roomName = new AtomicReference<>();
    private final AtomicReference<Double> temperature = new AtomicReference<>();
    private final AtomicReference<Double> temperatureFloor = new AtomicReference<>();
    private final AtomicReference<Double> temperatureFloorMin = new AtomicReference<>();
    private final AtomicReference<Double> temperatureFloorMax = new AtomicReference<>();
    private final AtomicReference<Double> setPointHome = new AtomicReference<>();
    private final AtomicReference<Double> setPointAway = new AtomicReference<>();
    private final AtomicReference<Double> setPointSleep = new AtomicReference<>();
    private final AtomicInteger batteryPercent = new AtomicInteger();
    private final AtomicReference<String> roomMode = new AtomicReference<>();
    private final AtomicBoolean manualControl = new AtomicBoolean();
    private final SDGPeerConnector connector;
    private final int roomNumber;

    public IconRoomHandler(SDGPeerConnector connector, int roomNumber) {
        this.connector = connector;
        this.roomNumber = roomNumber;
    }

    public String nameOrEmpty() {
        var name = this.roomName.get();
        return name == null ? "" : name;
    }

    @Override
    public void handlePacket(Dominion.Packet pkt) {

        switch (pkt.getMsgCode()) {
            case ROOM_FLOORTEMPERATURE:
                this.temperatureFloor.set(pkt.getDecimal());
               // reportTemperature(CHANNEL_TEMPERATURE_FLOOR, pkt.getDecimal());
                break;
            case ROOM_ROOMTEMPERATURE:
                this.temperature.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_TEMPERATURE_ROOM, pkt.getDecimal());
                break;
            case ROOM_SETPOINTATHOME:
                this.setPointHome.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_SETPOINT_COMFORT, pkt.getDecimal());
                break;
            case ROOM_SETPOINTASLEEP:
                this.setPointSleep.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_SETPOINT_ASLEEP, pkt.getDecimal());
                break;
            case ROOM_SETPOINTAWAY:
                this.setPointAway.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_SETPOINT_ECONOMY, pkt.getDecimal());
                break;
            case ROOM_FLOORTEMPERATUREMINIMUM:
                this.temperatureFloorMin.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_SETPOINT_MIN_FLOOR, pkt.getDecimal());
                break;
            case ROOM_FLOORTEMPERATUREMAXIMUM:
                this.temperatureFloorMax.set(pkt.getDecimal());
                //reportTemperature(CHANNEL_SETPOINT_MAX_FLOOR, pkt.getDecimal());
                break;
            case ROOM_BATTERYINDICATIONPERCENT:
                this.batteryPercent.set(pkt.getByte());
                //reportDecimal(CHANNEL_BATTERY, pkt.getByte());
                break;
            case ROOM_ROOMMODE:
                setRoomMode(pkt.getByte());
                //reportControlState(pkt.getByte());
                break;
            case ROOM_ROOMCONTROL:
                this.manualControl.set(pkt.getByte() == Icon.RoomControl.Manual);
                //reportSwitch(CHANNEL_MANUAL_MODE, );
                break;
            case ROOMNAME:
                this.roomName.set(pkt.getString());
                break;
        }
    }

    @Override
    public void ping() {
        //nop
    }

    public void refresh() {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_ROOMTEMPERATURE));
    }

    public void setHomeTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTATHOME, newTemperature));
        this.setPointHome.set(newTemperature);
    }

    public void setAwayTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTAWAY, newTemperature));
        this.setPointAway.set(newTemperature);
    }

    public void setSleepTemperature(double newTemperature) {
        this.connector.SendPacket(new Dominion.Packet(ROOM_FIRST + roomNumber, ROOM_SETPOINTASLEEP, newTemperature));
        this.setPointSleep.set(newTemperature);
    }

    private void setRoomMode(byte mode) {
        switch (mode) {
            case Icon.RoomMode.AtHome:
                this.roomMode.set("Home");
                break;
            case Icon.RoomMode.Away:
                this.roomMode.set("Away");
                break;
            case Icon.RoomMode.Asleep:
                this.roomMode.set("Sleep");
                break;
            case Icon.RoomMode.Fatal:
                this.roomMode.set("Fatal");
                break;
        }
    }

    public IconRoom toIconRoom() {
        return new IconRoom(this.roomName.get(), this.roomNumber, this.temperature.get(), this.setPointHome.get(),
                this.setPointAway.get(), this.batteryPercent.get());
    }
}
