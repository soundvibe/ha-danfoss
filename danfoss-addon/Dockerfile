ARG BUILD_FROM
FROM $BUILD_FROM

RUN apk add --no-cache openjdk21
RUN mkdir -p /share/danfoss-icon/

# Copy the JAR file to the container
COPY ha-danfoss-addon-0.0.1.jar /
COPY token.sh /

RUN chmod a+x /token.sh

CMD ["java", "-jar", "/ha-danfoss-addon-0.0.1.jar"]