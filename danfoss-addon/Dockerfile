ARG BUILD_FROM
ARG BUILD_VERSION

FROM eclipse-temurin:21-jdk-alpine AS build

# Copy only the necessary files for the build stage.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

RUN ./mvnw clean package -DskipTests


FROM $BUILD_FROM AS run

# Add labels for better image management
LABEL maintainer="lnaginionis@gmail.com"
LABEL version=$BUILD_VERSION
LABEL description="Home Assistant Danfoss Icon Add-on"

RUN mkdir -p /share/danfoss-icon/

RUN apk add --no-cache openjdk21

WORKDIR /

COPY run.sh /
RUN chmod a+x /run.sh

# Copy the JAR file to the container
COPY --from=build /target/ha-danfoss-addon-0.0.1.jar /app.jar

# Fixing JDK bug with M4 Mac chipsets
ENV JAVA_TOOL_OPTIONS="-XX:UseSVE=0"

CMD [ "/run.sh" ]