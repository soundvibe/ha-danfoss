package net.soundvibe.hasio.danfoss.protocol.config;

import io.github.sonic_amiga.opensdg.java.SDG;

import java.util.*;

public record DanfossBindingConfig(byte[] privateKey, byte[] publicKey, String userName) {

    public static DanfossBindingConfig create(String userName) {
        var newPrivkey = SDG.createPrivateKey();
        return new DanfossBindingConfig(
                newPrivkey,
                SDG.calcPublicKey(newPrivkey),
                Objects.requireNonNull(userName)
        );
    }
}
