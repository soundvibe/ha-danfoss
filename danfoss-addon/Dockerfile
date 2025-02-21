ARG BUILD_FROM
ARG BUILD_VERSION

# syntax=docker/dockerfile:1

# We use a multi-stage build setup.
# (https://docs.docker.com/build/building/multi-stage/)

###############################################################################
# Stage 1 of 2 (to create a "build" image)                                    #
###############################################################################
# https://hub.docker.com/_/eclipse-temurin
FROM eclipse-temurin:23-jdk-alpine AS build

# Smoke test to verify if java is available.
RUN java -version

### Build a downsized JRE
# Required for jlink's `--strip-debug` option.
RUN apk add --no-cache binutils
RUN jlink \
    --verbose \
    --add-modules ALL-MODULE-PATH \
    --compress=2 \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --output /minimal-jre

# Build and package the app.
# Copy only the necessary files for the build stage.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

ENV JAVA_TOOL_OPTIONS="-XX:UseSVE=0"

RUN ./mvnw clean package -B -DskipTests

###############################################################################
# Stage 2 of 2 (to create a downsized "container executable", ~161MB)         #
###############################################################################
FROM $BUILD_FROM AS run
# uncomment for local testing
#FROM ghcr.io/home-assistant/aarch64-base:latest AS run

# Add labels for better image management
LABEL maintainer="lnaginionis@gmail.com"
LABEL version=$BUILD_VERSION
LABEL description="Home Assistant Danfoss Icon Add-on"

RUN mkdir -p /share/danfoss-icon/

ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /

# Copy downsized JRE from builder image.
COPY --from=build /minimal-jre $JAVA_HOME
COPY run.sh /
RUN chmod a+x /run.sh

# Copy the JAR file to the container
COPY --from=build /target/ha-danfoss-addon-0.0.1.jar /app.jar

# Fixing JDK bug with M4 Mac chipsets
ENV JAVA_TOOL_OPTIONS="-XX:UseSVE=0"

EXPOSE 9199

CMD [ "/run.sh" ]