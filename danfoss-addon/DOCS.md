# Danfoss Icon Add-on

### Configuration parameters

| Parameter | Description |
|-----------|-------------|
| haUpdatePeriodInMinutes | How often Home Assistant will be updated with changes from Danfoss Icon master controller (default: 1 minute).
| sensorNameFmt | Temperature values are exposed as HA states(sensors), here you can choose how they are named (using `Java` [String.Format()](https://docs.oracle.com/javase/21/docs/api/java/util/Formatter.html#syntax)).

### Setting temperature

In order to set target temperature for thermostats from Home Assistant, main `configuration.yaml` should be updated, e.g.:

```yaml
rest_command:
  set_danfoss_home_temp:
    url: http://localhost:9199/command
    method: POST
    headers:
      accept: "application/json"
    payload: '{"command":"setHomeTemperature","value":{{ temperature }},"roomNumber":{{ roomNumber }}}'
    content_type: "application/json; charset=utf-8"
  set_danfoss_away_temp:
    url: http://localhost:9199/command
    method: POST
    headers:
      accept: "application/json"
    payload: '{"command":"setAwayTemperature","value":{{ temperature }},"roomNumber":{{ roomNumber }}}'
    content_type: "application/json; charset=utf-8"
```

This adds 2 rest commands which can be executed from scripts or automations, e.g.:

```yaml
service: rest_command.set_danfoss_home_temp
data:
  temperature: 22.5 # desired temperature.
  roomNumber: 3 # roomNumber is exposed as attribute in each danfoss temperature sensor entity.
```

### Climate entity
After we defined rest command, we can set up climate entities and use thermostats in HA dashboards, f.i. using custom [Template Climate](https://github.com/jcwillox/hass-template-climate) integration.
The `configuration.yaml` could look like this (given our sensor is exposed as `sensor.danfoss_0_temperature`):

```yaml
climate:
  - platform: climate_template
    name: Master Bedroom Thermostat
    unique_id: danfoss_0_thermostat
    min_temp: 5
    max_temp: 35
    temp_step: 0.5
    modes:
      - "off"
      - "heat"
    hvac_mode_template: "{{ state_attr('sensor.danfoss_0_temperature', 'mode') }}"
    current_temperature_template: "{{ states('sensor.danfoss_0_temperature') }}"
    target_temperature_template: "{{ state_attr('sensor.danfoss_0_temperature', 'temperature_home') }}"
    set_temperature:
      service: rest_command.set_danfoss_home_temp
      data:
        temperature: "{{ temperature }}"
        roomNumber: "{{ state_attr('sensor.danfoss_0_temperature', 'room_number') }}"
```

The result:

![img.png](img.png)