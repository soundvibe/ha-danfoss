ARG BUILD_FROM
FROM $BUILD_FROM

RUN apk add --no-cache openjdk21

# Copy the JAR file to the container
COPY ../target/ha-danfoss-0.0.1.jar /opt/app/

RUN mkdir -p /share/danfoss-icon/

EXPOSE 9090

CMD ["java", "-showversion", "-jar", "/opt/app/ha-danfoss-0.0.1.jar"]