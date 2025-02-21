#!/usr/bin/with-contenv bashio

echo "Starting app..."

JAVA_ARGS=""
M4_FIX_ENABLED="$(bashio::config 'm4FixEnabled')"

if [[ "$M4_FIX_ENABLED" == "true" ]]; then
  JAVA_ARGS="-XX:UseSVE=0"
fi

echo $JAVA_ARGS

java --enable-preview $JAVA_ARGS -jar app.jar