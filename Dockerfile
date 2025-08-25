FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /bpsim

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
COPY bpsim-common/pom.xml ./bpsim-common/
COPY bpsim-server/pom.xml ./bpsim-server/
COPY bpsim-cli/pom.xml ./bpsim-cli/

RUN mvn dependency:go-offline

COPY bpsim-common/ ./bpsim-common/
COPY bpsim-server/ ./bpsim-server/
COPY bpsim-cli/ ./bpsim-cli/

RUN mvn clean install

EXPOSE 9000 8080


CMD ["mvn", "-pl", "bpsim-server", "quarkus:dev", "-Dquarkus.http.host=0.0.0.0"]

