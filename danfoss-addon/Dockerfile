ARG BUILD_FROM
FROM $BUILD_FROM

RUN mkdir -p /share/danfoss-icon/

RUN apk add --no-cache openjdk21

WORKDIR /

COPY run.sh /
RUN chmod a+x /run.sh

# Copy the JAR file to the container
# Fixing JDK bug with M4 Mac chipsets
ENV JAVA_TOOL_OPTIONS="-XX:UseSVE=0"

COPY ha-danfoss-addon-0.0.1.jar /app.jar

CMD [ "/run.sh" ]