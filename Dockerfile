FROM ghcr.io/graalvm/native-image-community:21 AS build

WORKDIR /bpsim

RUN microdnf install -y maven

COPY .libs/ ./libs/

RUN mvn install:install-file \
    -Dfile=libs/voltha-protos-5.1.2-internship.jar \
    -DgroupId=org.netsia.voltha \
    -DartifactId=voltha-protos \
    -Dversion=5.1.2-internship \
    -Dpackaging=jar

RUN mvn install:install-file \
    -Dfile=libs/packet-lib-internship-1.0-SNAPSHOT.jar \
    -DgroupId=com.netsia.control.lib.api.packet.parsed \
    -DartifactId=packet-lib-internship \
    -Dversion=1.0-SNAPSHOT \
    -Dpackaging=jar

COPY pom.xml .
COPY bpsim-common/ ./bpsim-common/
COPY bpsim-server/ ./bpsim-server/
COPY bpsim-cli/ ./bpsim-cli/

RUN mvn clean install

WORKDIR /bpsim/bpsim-cli
RUN native-image \
    -cp target/bpsim-cli-1.0-SNAPSHOT.jar \
    -H:ReflectionConfigurationFiles=target/classes/META-INF/native-image/picocli-generated/reflect-config.json \
    -H:Name=bpsimctl \
    com.argela.BpsimctlCommand

# Runtime stage
FROM maven:3.9.11-eclipse-temurin-21

WORKDIR /bpsim

COPY --from=build /root/.m2 /root/.m2
COPY --from=build /bpsim .
COPY --from=build /bpsim/bpsim-cli/bpsimctl /usr/local/bin/bpsimctl
RUN chmod +x /usr/local/bin/bpsimctl

CMD ["mvn", "-pl", "bpsim-server", "quarkus:dev", "-Dquarkus.http.host=0.0.0.0", "-Dquarkus.test.continuous-testing=disabled"]
