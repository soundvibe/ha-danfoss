package net.soundvibe.hasio.danfoss.data;

public enum HeatingState {

    OFF, HEAT, COOL;

    public static HeatingState from(boolean isOn, boolean coolingEnabled) {
        return isOn ? coolingEnabled ? COOL : HEAT : OFF;
    }
}
