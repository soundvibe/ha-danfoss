ARG BUILD_FROM
FROM $BUILD_FROM
#FROM ghcr.io/home-assistant/aarch64-base:latest

RUN mkdir -p /share/danfoss-icon/

RUN apk add --no-cache openjdk21 curl

WORKDIR /

COPY run.sh /
RUN chmod a+x /run.sh

COPY config.yaml /tmp/config.yaml

# Download the JAR matching this add-on's version.
# Falls back to 'latest' if the versioned release doesn't exist yet.
RUN VERSION=$(grep '^version:' /tmp/config.yaml | head -1 | sed 's/version: *"//;s/"$//') && \
    echo "Add-on version: $VERSION" && \
    if curl -L -f -o /app.jar "https://github.com/soundvibe/ha-danfoss/releases/download/v${VERSION}/ha-danfoss-addon.jar" 2>/dev/null; then \
      echo "Downloaded JAR for v${VERSION}"; \
    elif curl -L -f -o /app.jar "https://github.com/soundvibe/ha-danfoss/releases/download/${VERSION}/ha-danfoss-addon.jar" 2>/dev/null; then \
      echo "Downloaded JAR for ${VERSION}"; \
    else \
      echo "Versioned release not found, falling back to latest" && \
      curl -L -o /app.jar "https://github.com/soundvibe/ha-danfoss/releases/latest/download/ha-danfoss-addon.jar"; \
    fi

CMD [ "/run.sh" ]