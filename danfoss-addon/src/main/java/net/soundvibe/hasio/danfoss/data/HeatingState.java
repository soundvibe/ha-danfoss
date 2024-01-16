package net.soundvibe.hasio.danfoss.data;

public enum HeatingState {

    OFF, HEAT;

    public static HeatingState from(boolean b) {
        return b ? HEAT : OFF;
    }
}
