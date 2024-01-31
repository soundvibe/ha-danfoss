package net.soundvibe.hasio.ha.model;

import java.util.List;
import java.util.Map;

public record MQTTClimateEntity(String unique_id,
                                String name,
                                List<String> modes,
                                double min_temp, double max_temp, double temp_step,
                                Map<String,String> device,
                                String availability_topic, String availability_template,
                                String current_temperature_topic, String current_temperature_template,
                                String mode_state_topic, String mode_state_template,
                                List<String> preset_modes, String preset_mode_command_topic,
                                String preset_mode_state_topic, String preset_mode_value_template,
                                String temperature_command_topic, String temperature_command_template,
                                String temperature_state_topic, String temperature_state_template,
                                String temperature_high_state_topic, String temperature_high_state_template,
                                String temperature_low_state_topic, String temperature_low_state_template,
                                String json_attributes_topic, String json_attributes_template
                                ) {}
