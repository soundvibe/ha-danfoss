ARG BUILD_FROM
FROM $BUILD_FROM

RUN apk add --no-cache openjdk21
RUN mkdir -p /share/danfoss-icon/

# Copy the JAR file to the container
COPY ha-danfoss-addon-0.0.1.jar /

EXPOSE 9090

CMD ["java", "-showversion", "-jar", "/ha-danfoss-addon-0.0.1.jar"]