package net.soundvibe.hasio.ha.model;

import java.util.Map;

public record MQTTSensorEntity(String unique_id,
                               String name,
                               Map<String, String> device,
                               String availability_topic, String availability_template,
                               String state_topic, String value_template,
                               String device_class, String state_class,
                               String unit_of_measurement, String entity_category) {}
