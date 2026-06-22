package net.soundvibe.hasio.danfoss.data;

import net.soundvibe.hasio.ha.model.State;

import java.time.Instant;
import java.util.Map;

import static java.util.Map.entry;

public record IconMaster(
        String houseName,
        double vacationSetPoint,
        double pauseSetPoint,
        String hardwareRevision,
        String softwareRevision,
        String serialNumber,
        int softwareBuildRevision,
        int connectionCount,
        Instant productionDate) {

    public State toState() {
        return new State(Instant.now().toString(), Map.ofEntries(
                entry("device_class", "timestamp"),
                entry("state_class", "measurement"),
                entry("house_name", String.valueOf(houseName)),
                entry("pause_set_point", String.valueOf(pauseSetPoint)),
                entry("vacation_set_point", String.valueOf(vacationSetPoint)),
                entry( "hardware_revision", String.valueOf(hardwareRevision)),
                entry( "software_revision", String.valueOf(softwareRevision)),
                entry( "serial_number", String.valueOf(serialNumber)),
                entry("software_build_revision", String.valueOf(softwareBuildRevision)),
                entry("connection_count", String.valueOf(connectionCount)),
                entry("production_date", String.valueOf(productionDate))
        ));
    }

}
