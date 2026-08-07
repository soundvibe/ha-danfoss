package net.soundvibe.hasio.ha.model;

import java.util.Map;

public record MQTTSensorEntity(
        String unique_id,
        String name,
        String componentType, // should be "sensor"
        String state_topic,
        String value_template,
        String unit_of_measurement,
        String device_class,
        String state_class,
        Map<String, String> device
) {}
