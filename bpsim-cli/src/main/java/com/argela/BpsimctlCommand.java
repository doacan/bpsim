package com.argela;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Command(name = "bpsimctl",
        description = "BPSIM Control CLI Tool",
        mixinStandardHelpOptions = true,
        subcommands = {
                BpsimctlCommand.DhcpCommand.class
        })
class BpsimctlCommand implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new BpsimctlCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Ana komut çalıştırıldığında help göster
        CommandLine.usage(this, System.out);
    }@Command(name = "dhcp",
            description = "DHCP simulation commands",
            subcommands = {
                    BpsimctlCommand.DhcpCommand.DhcpDiscoveryCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpOfferCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpRequestCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpAckCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpListCommand.class
            })
    static class DhcpCommand implements Runnable {

        @Override
        public void run() {
            // Alt komut belirtilmediğinde help göster
            CommandLine.usage(this, System.out);
        }

        // Base class for DHCP packet commands
        static abstract class DhcpPacketCommand implements Runnable {
            @Parameters(index = "0", description = "PON Port")
            int ponPort;

            @Parameters(index = "1", description = "ONU ID")
            int onuId;

            @Parameters(index = "2", description = "UNI ID")
            int uniId;

            @Parameters(index = "3", description = "GEM Port")
            int gemPort;

            @Parameters(index = "4", description = "C-Tag")
            int cTag;

            @CommandLine.Option(names = {"-u", "--url"}, description = "Server URL (default: http://localhost:8080)")
            String serverUrl = "http://localhost:8080";

            protected void sendDhcpRequest(String packetType) {
                DhcpSimulationRequest request = new DhcpSimulationRequest(
                        packetType, ponPort, onuId, uniId, gemPort, cTag
                );

                try (HttpClient client = HttpClient.newHttpClient()) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
                    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

                    String jsonPayload = objectMapper.writeValueAsString(request);

                    HttpRequest httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create(serverUrl + "/dhcp"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                            .build();

                    HttpResponse<String> response = client.send(httpRequest,
                            HttpResponse.BodyHandlers.ofString());

                    System.out.println("DHCP " + packetType + " request sent:");
                    System.out.println(jsonPayload);
                    System.out.println("Response status: " + response.statusCode());
                    System.out.println("Response body: " + response.body());

                } catch (Exception e) {
                    System.err.println("Error sending DHCP " + packetType + " request: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        @Command(name = "discovery", description = "Send DHCP DISCOVERY packet")
        static class DhcpDiscoveryCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("DISCOVERY");
            }
        }

        @Command(name = "offer", description = "Send DHCP OFFER packet")
        static class DhcpOfferCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("OFFER");
            }
        }

        @Command(name = "request", description = "Send DHCP REQUEST packet")
        static class DhcpRequestCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("REQUEST");
            }
        }

        @Command(name = "ack", description = "Send DHCP ACK packet")
        static class DhcpAckCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("ACK");
            }
        }

        @Command(name = "list", description = "List DHCP sessions or configurations")
        static class DhcpListCommand implements Runnable {
            @Option(names = {"-f", "--filter"}, description = "Filter results (optional)")
            String filter;

            @Option(names = {"-u", "--url"}, description = "Server URL (default: http://localhost:8080)")
            String serverUrl = "http://localhost:8080";

            @Override
            public void run() {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    String endpoint = serverUrl + "/dhcp/list";
                    if (filter != null && !filter.trim().isEmpty()) {
                        endpoint += "?filter=" + filter;
                    }

                    HttpRequest httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    HttpResponse<String> response = client.send(httpRequest,
                            HttpResponse.BodyHandlers.ofString());

                    System.out.println("DHCP List Response:");
                    System.out.println("Response status: " + response.statusCode());
                    System.out.println("Response body: " + response.body());

                } catch (Exception e) {
                    System.err.println("Error getting DHCP list: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        @Command(name = "creat", description = "Create DHCP devices")
        static class DhcpCreateCommand implements Runnable {
            @Parameters(index = "0", description = "PON Port")
            int ponPort;

            @Parameters(index = "1", description = "ONU ID")
            int onuId;

            @Override
            public void run() {
                //sendDhcpRequest("ACK");
            }
        }
    }
}
