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