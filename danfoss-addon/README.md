# Home Assistant Addon for [Danfoss Icon Controller](https://www.danfoss.com/en-gb/products/dhs/smart-heating/smart-heating/danfoss-icon/).


## Summary

This addon allows you to integrate heating solutions by Danfoss company with Home Assistant.

## Setup

### Pairing

After installing addon, open it's Web UI and enter one time code and username. 
One time code can be retrieved from Danfoss Icon official Android application by going to `Settings -> Share house` (paste code without dashes)
### Note 
As of now, only code, generated from Danfoss `Android` app is supported. 
It looks like iPhone app uses different protocol which isn't supported by this addon. 
If you own iPhone, try finding Android phone to do the pairing, and once it completes, you can uninstall the app afterwards.

Username can be whatever name you like.
Click `Start` button for pairing to start.
If everything goes well, you will see the success message.

### Config

After successful pairing process, addon will try to write config data needed to connect to Danfoss cloud into `/share/danfoss-icon/danfoss_config.json`.
If this file is present, the pairing process won't be needed and the addon will automatically connect to Danfoss servers.

### Home Assistant

All house thermostats will be exposed to Home Assistant as temperature sensors, e.g. `sensor.danfoss_{room_number}_temperature`.
Remaining battery percentage is written into entity attributes - `battery_level`.

If MQTT is enabled in add-on configuration, all house thermostats will be exposed as climate devices in Home Assistant.

### Documentation

Documentation on how Home Assistant [climate](https://developers.home-assistant.io/docs/core/entity/climate/) entities (thermostats) might be created can be found [here](DOCS.md).

## Donations

If this repository was useful to you and if you are willing to pay for it, feel free to send any amount through paypal:

[![paypal](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://paypal.me/soundvibe)

