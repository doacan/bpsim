package com.argela;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import picocli.CommandLine;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Command(name = "bpsimctl",
        description = "BPSIM Control CLI Tool",
        mixinStandardHelpOptions = true,
        subcommands = {
                BpsimctlCommand.DhcpCommand.class,
                BpsimctlCommand.DhcpListCommand.class,
                BpsimctlCommand.DhcpStormCommand.class,
                BpsimctlCommand.InfoCommand.class,
                BpsimctlCommand.StopCommand.class,
                BpsimctlCommand.ClearCommand.class
        })
class BpsimctlCommand implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new BpsimctlCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "list",
            mixinStandardHelpOptions = true,
            description = "List DHCP sessions with filtering options")
    static class DhcpListCommand implements Runnable {
        // ANSI color codes
        private static final String RESET = "\u001B[0m";
        private static final String GREEN = "\u001B[32m";

        @Option(names = {"-f", "--filter"}, description = "General text filter (searches across multiple fields)")
        String filter;

        @Option(names = {"-v","--vlan"}, description = "Filter by VLAN ID")
        Integer vlanId;

        @Option(names = {"-p","--pon"}, description = "Filter by PON Port")
        Integer ponPort;

        @Option(names = {"-o","--onu"}, description = "Filter by ONU ID")
        Integer onuId;

        @Option(names = {"-u","--uni"}, description = "Filter by UNI ID")
        Integer uniId;

        @Option(names = {"-g","--gem"}, description = "Filter by GEM Port")
        Integer gemPort;

        @Option(names = {"-s","--state"}, description = "Filter by DHCP state")
        String state;

        @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
        String serverUrl = "http://localhost:8080";

        @Option(names = {"-w", "--wide"}, description = "Show all columns (wider output)")
        boolean wideOutput = false;

        @Override
        public void run() {
            try(HttpClient client = HttpClient.newHttpClient()) {
                // Build URL with query parameters
                StringBuilder urlBuilder = new StringBuilder(serverUrl + "/dhcp/list?");
                List<String> queryParams = new ArrayList<>();

                if (filter != null && !filter.trim().isEmpty()) {
                    queryParams.add("filter=" + java.net.URLEncoder.encode(filter, StandardCharsets.UTF_8));
                }
                if (vlanId != null) {
                    queryParams.add("vlanId=" + vlanId);
                }
                if (ponPort != null) {
                    queryParams.add("ponPort=" + ponPort);
                }
                if (onuId != null) {
                    queryParams.add("onuId=" + onuId);
                }
                if (uniId != null) {
                    queryParams.add("uniId=" + uniId);
                }
                if (gemPort != null) {
                    queryParams.add("gemPort=" + gemPort);
                }
                if (state != null && !state.trim().isEmpty()) {
                    queryParams.add("state=" + java.net.URLEncoder.encode(state, StandardCharsets.UTF_8));
                }

                String finalUrl;
                if (!queryParams.isEmpty()) {
                    finalUrl = urlBuilder + "?" + String.join("&", queryParams);
                } else {
                    finalUrl = urlBuilder.toString();
                }

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(finalUrl))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("Error: HTTP " + response.statusCode());
                    return;
                }

                // JSON parse
                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> list = mapper.readValue(
                        response.body(),
                        mapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );

                if (list.isEmpty()) {
                    System.out.println("No data available.");
                    return;
                }

                printAsciiTable(list);

            } catch (Exception e) {
                System.err.println("Error getting DHCP list: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void printAsciiTable(List<Map<String, Object>> data) {
            // Columns to display
            String[] columns;
            if (wideOutput) {
                columns = new String[]{"id", "clientMac", "ipAddress", "state", "dhcpDuration",
                        "vlanId", "ponPort", "onuId", "uniId", "gemPort", "gateway"};
            } else {
                columns = new String[]{"id", "clientMac", "ipAddress", "state", "dhcpDuration",
                        "vlanId", "ponPort", "onuId", "uniId", "gemPort"};
            }

            // Get terminal width
            int terminalWidth = getTerminalWidth();

            // Calculate max column widths
            int[] maxWidths = calculateColumnWidths(data, columns, terminalWidth);

            AsciiTable at = new AsciiTable();

            // Set column widths
            CWC_LongestLine cwc = new CWC_LongestLine();
            for (int i = 0; i < columns.length; i++) {
                cwc.add(i, maxWidths[i]);
            }
            at.getRenderer().setCWC(cwc);

            at.addRule();

            // Friendly headers
            String[] friendlyHeaders = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                friendlyHeaders[i] = getFriendlyColumnName(columns[i]);
            }
            at.addRow((Object[]) friendlyHeaders);
            at.addRule();

            for (Map<String, Object> row : data) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < columns.length; i++) {
                    String value = formatColumnValue(row, columns[i], maxWidths[i]);
                    values.add(value);
                }
                at.addRow(values);
                at.addRule();
            }

            // Render table and highlight ACKNOWLEDGED rows
            String tableOutput = at.render();
            String[] lines = tableOutput.split("\n");

            int dataRowIndex = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];

                // Data rows start at index 3 and appear every other line (3, 5, 7, 9...)
                if (i >= 3 && (i - 3) % 2 == 0) {
                    if (dataRowIndex < data.size()) {
                        Map<String, Object> row = data.get(dataRowIndex);
                        String state = row.get("state") != null ? row.get("state").toString() : "";

                        if ("ACKNOWLEDGED".equals(state)) {
                            System.out.println(colorizeTableRow(line));
                        } else {
                            System.out.println(line);
                        }
                        dataRowIndex++;
                    } else {
                        System.out.println(line);
                    }
                } else {
                    // Headers, separators, etc.
                    System.out.println(line);
                }
            }

            // Summary
            System.out.println("\nTotal devices: " + data.size());
            if (!data.isEmpty()) {
                // State distribution
                Map<String, Long> stateCount = data.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                row -> row.get("state") != null ? row.get("state").toString() : "UNKNOWN",
                                java.util.stream.Collectors.counting()));

                System.out.print("State distribution: ");
                stateCount.forEach((state, count) -> System.out.print(state + ":" + count + " "));
                System.out.println();
            }
        }

        private String colorizeTableRow(String line) {
            StringBuilder result = new StringBuilder();
            boolean insideCell = false;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);

                if (c == '│') {
                    if (insideCell) {
                        result.append(RESET);
                        insideCell = false;
                    }
                    result.append(c);
                    if (i + 1 < line.length()) {
                        insideCell = true;
                        result.append(GREEN);
                    }
                } else {
                    result.append(c);
                }
            }

            if (insideCell) {
                result.append(RESET);
            }

            return result.toString();
        }

        private String getFriendlyColumnName(String column) {
            return switch (column) {
                case "id" -> "ID";
                case "clientMac" -> "MAC Address";
                case "ipAddress" -> "IP Address";
                case "state" -> "State";
                case "dhcpDuration" -> "DHCP Duration";
                case "vlanId" -> "VLAN";
                case "ponPort" -> "PON";
                case "onuId" -> "ONU";
                case "uniId" -> "UNI";
                case "gemPort" -> "GEM";
                case "gateway" -> "Gateway";
                default -> column;
            };
        }

        private String formatColumnValue(Map<String, Object> row, String column, int maxWidth) {
            Object val = row.get(column);
            String value;

            // Special formatting for DHCP Duration
            if ("dhcpDuration".equals(column)) {
                Long duration = getDhcpDurationMs(row);
                if (duration != null) {
                    value = duration + "ms";
                } else {
                    value = "-";
                }
            } else {
                value = val != null ? val.toString() : "";
            }

            if (value.length() > maxWidth) {
                value = value.substring(0, maxWidth - 3) + "...";
            }

            return value;
        }

        private Long getDhcpDurationMs(Map<String, Object> row) {
            try {
                String state = row.get("state") != null ? row.get("state").toString() : "";

                if ("ACKNOWLEDGED".equals(state)) {
                    // For ACKNOWLEDGED state, use completion time
                    Object completionTimeObj = row.get("dhcpCompletionTimeMs");
                    if (completionTimeObj != null) {
                        return Long.valueOf(completionTimeObj.toString());
                    }
                }

                // For other states, calculate time from start to now
                Object dhcpStartTimeObj = row.get("dhcpStartTime");
                if (dhcpStartTimeObj != null) {
                    String dhcpStartTimeStr = dhcpStartTimeObj.toString();
                    java.time.Instant dhcpStartTime = java.time.Instant.parse(dhcpStartTimeStr);
                    return java.time.Duration.between(dhcpStartTime, java.time.Instant.now()).toMillis();
                }
            } catch (Exception e) {
                System.err.println("Error parsing DHCP duration: " + e.getMessage());
            }
            return null;
        }

        private int getTerminalWidth() {
            try (org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder.terminal()) {
                return terminal.getWidth();
            } catch (Exception e) {
                return 120;
            }
        }

        private int[] calculateColumnWidths(List<Map<String, Object>> data, String[] columns, int terminalWidth) {
            int[] maxWidths = new int[columns.length];

            // Get header lengths as starting point
            for (int i = 0; i < columns.length; i++) {
                maxWidths[i] = getFriendlyColumnName(columns[i]).length();
            }

            // Find longest values in data
            for (Map<String, Object> row : data) {
                for (int i = 0; i < columns.length; i++) {
                    String formattedValue = formatColumnValue(row, columns[i], Integer.MAX_VALUE);
                    if (formattedValue.length() > maxWidths[i]) {
                        maxWidths[i] = formattedValue.length();
                    }
                }
            }

            // Minimum and maximum width limits
            for (int i = 0; i < maxWidths.length; i++) {
                maxWidths[i] = Math.max(maxWidths[i], 4); // Minimum 4 characters
                maxWidths[i] = Math.min(maxWidths[i], 25); // Maximum 25 characters
            }

            // Calculate total width (borders require +3 for each column)
            int totalWidth = 0;
            for (int width : maxWidths) {
                totalWidth += width + 3; // 3 = | + space + space
            }
            totalWidth += 1; // For final border

            // If exceeds terminal width, proportionally reduce column widths
            if (totalWidth > terminalWidth) {
                double ratio = (double)(terminalWidth - (columns.length * 3) - 1) / (totalWidth - (columns.length * 3) - 1);
                for (int i = 0; i < maxWidths.length; i++) {
                    maxWidths[i] = Math.max(3, (int)(maxWidths[i] * ratio)); // Minimum 3 characters
                }
            }

            return maxWidths;
        }
    }

    @Command(name = "storm",
            mixinStandardHelpOptions = true,
            description = "Create DHCP storm simulation")
    static class DhcpStormCommand implements Runnable {
        @Parameters(index = "0", description = "Rate (packets/second) - use either rate or intervalSec")
        Integer rate;

        @Parameters(index = "1", description = "Interval in seconds - use either rate or intervalSec", arity = "0..1")
        Double intervalSec;

        @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
        String serverUrl = "http://localhost:8080";

        @Override
        public void run() {
            // Validate that exactly one of rate or intervalSec is provided
            if (rate != null && rate == 0 && intervalSec != null && intervalSec > 0) {
                rate = null;
            } else if (rate != null && rate > 0) {
                intervalSec = null;
            } else {
                System.err.println("Error: Invalid parameters");
                System.err.println("Usage: storm <rate> [intervalSec]");
                System.err.println("  - For rate-based: storm 100");
                System.err.println("  - For interval-based: storm 0 5");
                return;
            }

            try {
                HttpClient client = HttpClient.newHttpClient();
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
                objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

                // Create request payload
                DhcpStormRequest request = new DhcpStormRequest(rate, intervalSec);
                String jsonPayload = objectMapper.writeValueAsString(request);

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/dhcp/storm"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                HttpResponse<String> response = client.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                System.out.println("DHCP Storm request sent:");
                System.out.println(jsonPayload);
                System.out.println("Response status: " + response.statusCode());
                System.out.println("Response body: " + response.body());

            } catch (Exception e) {
                System.err.println("Error sending DHCP Storm request: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Command(name = "dhcp",
            description = "DHCP simulation commands",
            mixinStandardHelpOptions = true,
            subcommands = {
                    BpsimctlCommand.DhcpCommand.DhcpDiscoveryCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpOfferCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpRequestCommand.class,
                    BpsimctlCommand.DhcpCommand.DhcpAckCommand.class
            })
    static class DhcpCommand implements Runnable {

        @Override
        public void run() {
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

            @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
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

        @Command(name = "discovery",
                mixinStandardHelpOptions = true,
                description = "Send DHCP DISCOVERY packet")
        static class DhcpDiscoveryCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("DISCOVERY");
            }
        }

        @Command(name = "offer",
                mixinStandardHelpOptions = true,
                description = "Send DHCP OFFER packet")
        static class DhcpOfferCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("OFFER");
            }
        }

        @Command(name = "request",
                mixinStandardHelpOptions = true,
                description = "Send DHCP REQUEST packet")
        static class DhcpRequestCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("REQUEST");
            }
        }

        @Command(name = "ack",
                mixinStandardHelpOptions = true,
                description = "Send DHCP ACK packet")
        static class DhcpAckCommand extends DhcpPacketCommand {
            @Override
            public void run() {
                sendDhcpRequest("ACK");
            }
        }
    }

    @Command(name = "info",
            mixinStandardHelpOptions = true,
            description = "Show system configuration information")
    static class InfoCommand implements Runnable {

        @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
        String serverUrl = "http://localhost:8080";

        @Override
        public void run() {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/dhcp/info"))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("Error: HTTP " + response.statusCode());
                    return;
                }

                // JSON parse
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> info = mapper.readValue(response.body(), Map.class);

                // Display information
                System.out.println("BPSIM System Configuration:");
                System.out.println("==========================");
                System.out.println("PON Ports: " + info.get("ponPortCount"));
                System.out.println("ONU Ports: " + info.get("onuPortCount"));
                System.out.println("Total Devices Capacity: " + info.get("totalDeviceCapacity"));

            } catch (Exception e) {
                System.err.println("Error getting system info: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Command(name = "stop",
            mixinStandardHelpOptions = true,
            description = "Stop currently running DHCP storm")
    static class StopCommand implements Runnable {

        @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
        String serverUrl = "http://localhost:8080";

        @Override
        public void run() {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/dhcp/storm/cancel"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();

                HttpResponse<String> response = client.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    // JSON parse
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> result = mapper.readValue(response.body(), Map.class);

                    System.out.println("✓ " + result.get("status"));
                } else {
                    System.err.println("Error: HTTP " + response.statusCode());
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> errorResult = mapper.readValue(response.body(), Map.class);
                        System.err.println("Message: " + errorResult.get("error"));
                    } catch (Exception e) {
                        System.err.println("Response: " + response.body());
                    }
                }

            } catch (Exception e) {
                System.err.println("Error stopping storm: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @Command(name = "clear",
            mixinStandardHelpOptions = true,
            description = "Clear all DHCP devices from the system")
    static class ClearCommand implements Runnable {

        @Option(names = {"-U", "--url"}, description = "Server URL (default: http://localhost:8080)")
        String serverUrl = "http://localhost:8080";

        @Override
        public void run() {
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/dhcp/clear-all"))
                        .header("Content-Type", "application/json")
                        .DELETE()
                        .build();

                HttpResponse<String> response = client.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> result = mapper.readValue(response.body(), Map.class);
                    System.out.println("✓ " + result.get("message"));
                    Object clearedCount = result.get("clearedCount");
                    if (clearedCount != null) {
                        System.out.println("Devices cleared: " + clearedCount);
                    }
                } else {
                    System.err.println("Error: HTTP " + response.statusCode());
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> errorResult = mapper.readValue(response.body(), Map.class);
                        System.err.println("Message: " + errorResult.get("error"));
                    } catch (Exception e) {
                        System.err.println("Response: " + response.body());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error clearing devices: " + e.getMessage());
            }
        }
    }
}
