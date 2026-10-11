# MQTT climate entities

Enable MQTT in the add-on options and enable discovery in Home Assistant's MQTT integration. Each discovered room uses the existing identity `danfoss_icon_thermostat_room_N`, discovery topic `homeassistant/climate/danfoss_icon_thermostat_room_N/config`, state topic `danfoss/icon/N/state`, and command topic `danfoss/icon/N/set`. Room numbers are the controller's numbers, including room 0.

Discovery updates replace the configuration at the same topic and unique ID. Existing entity registry entries, entity IDs, device grouping and battery sensors are preserved. Do not create additional YAML climate entities or delete the existing MQTT entities to apply this update.

## Temperature and presets

The climate entity displays one target: the Home, Away or Sleep setpoint selected by the controller's current room preset. Home and Away temperatures are not the lower and upper targets of a temperature range. The controller's actual temperature limits still define `min_temp` and `max_temp`.

Publish a non-retained command to `danfoss/icon/1/set`, for example:

```json
{"temperature_target":22.5,"room_number":1}
```

`room_number` is optional; if supplied it must match the topic. The command changes the active preset's setpoint, preserving the other two setpoints. Values must be finite and inside the room's reported limits. Home Assistant's discovered temperature control generates this JSON automatically.

The existing preset selector sends `home`, `away` or `sleep` to the same topic. JSON such as `{"preset":"away"}` is also accepted. Preset commands write the room mode and request feedback; they do not change the room's Manual/Auto/Schedule configuration. Home/Away/Sleep writes and controller feedback have been verified on one Icon underfloor heating installation.

Both MQTT and the existing REST `POST /command` temperature commands (`setHomeTemperature`, `setAwayTemperature`, `setSleepTemperature`) send a controller write followed by a read request. Reported temperatures and presets change only on controller feedback, not on an optimistic cache assignment. Confirmed controller changes are checked locally every 500 ms and published to MQTT when the room snapshot changes. This adds no controller polling or cloud requests. Full discovery/state refreshes and the legacy REST sensor updates still use the configured update interval. Publication is serialized and snapshots are taken inside that lock so overlapping refreshes cannot send an older snapshot after newer feedback. Invalid REST room numbers or command names return HTTP 400 instead of reporting success.

## HVAC activity

The protocol's `ROOM_HEATINGSTATE` (4115), exposed as the legacy `mode` attribute, indicates current activity: Off, Heat or Cool. Off means no current heating/cooling demand; it does not establish that the thermostat is disabled.

The new `hvac_action` is `idle`, `heating` or `cooling`. For heating installations, `hvac_mode` remains `heat` while the room is idle. A reported cooling activity yields `cool`. Idle cooling operation cannot be distinguished using this activity field alone and is currently inferred as heating; decoding the controller's heating/cooling configuration would be needed to resolve that case.

No verified HVAC enable/disable command exists in the current protocol implementation. Discovery therefore advertises only the inferred current mode and no mode command topic. An `off`/`heat` selector would promise a command the add-on cannot execute. The raw `mode` attribute remains unchanged for existing automations.

## Restart behavior

Discovery messages are retained. Subscriptions are recreated after every MQTT clean-session connection, including automatic reconnect after a broker restart. A `homeassistant/status` birth message with payload `online` republishes discovery and current state. Partially initialized rooms are not published during startup. Received retained command messages are ignored to prevent replaying an old target or preset.

Tests use a local MQTT broker to exercise add-on client recreation, late discovery subscribers, HA birth messages, broker restart and subsequent command delivery. Protocol tests cover controller feedback, preset-specific writes, and all ten room addresses. Run them with Java 21 (preview features enabled by Maven):

```sh
./mvnw test
```

These tests do not replace hardware verification. Before release, install the updated build into the existing add-on, record the entity IDs, test HA-to-controller and controller-to-HA changes, restore the original targets/presets, then restart the add-on, MQTT broker and HA separately. Check that each room still has one climate entity with its original entity ID and that temperature commands still work after each restart.

### Hardware checks, 2026-10-12

A local build of commit `d77126c` was exercised on an existing Icon underfloor heating installation with ten rooms, using its saved pairing and existing MQTT identities. Checks completed so far:

- HA displayed a single active target and heating mode with idle action for all ten rooms.
- A HA target change reached the controller and was independently confirmed in the Danfoss app. Changing the target back in the Danfoss app reached HA.
- Home/Away/Sleep writes returned the requested room mode. Target changes in each active preset affected only that preset's temperature.
- All original profile temperatures and presets were restored and compared across the ten rooms.
- The twenty climate/battery entity IDs were unchanged, and all twenty discovery messages were received as retained messages by a new subscriber.
- After restarting Home Assistant, all ten rooms retained their original profiles and presets.
- After restarting the add-on, the ten rooms retained their original profiles and presets. The direct MQTT command `{"temperature_target":22.5,"room_number":1}` was accepted, and the existing REST `setHomeTemperature` command restored the original target.

These checks establish command/feedback behavior; they do not measure the floor's thermal response or verify HVAC enable/disable and idle cooling behavior. A production MQTT broker restart has not been performed; broker reconnect is covered by the automated broker tests.

## Operational improvements

MQTT passwords are redacted in diagnostic options logging; pairing private keys and Supervisor tokens are not logged. Configuration serialization and saved pairing data remain unchanged. The launcher uses `exec java` so the Java process receives the container stop signal and can run its MQTT shutdown hook.
