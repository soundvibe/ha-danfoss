#!/usr/bin/with-contenv bashio

echo "Starting app..."

M4_FIX_ENABLED="$(bashio::config 'm4FixEnabled')"

if [[ "$M4_FIX_ENABLED" == "true" ]]; then
  export JAVA_TOOL_OPTIONS="-XX:UseSVE=0"
fi

echo "$JAVA_TOOL_OPTIONS"

java --enable-preview -jar app.jar