package com.argela;

import com.google.protobuf.ByteString;
import com.netsia.control.lib.api.packet.parsed.*;
import com.netsia.control.lib.api.packet.parsed.dhcp.DhcpOption;
import com.netsia.control.lib.api.packet.parsed.dhcp.DhcpRelayAgentOption;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;

import io.smallrye.context.api.ManagedExecutorConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.opencord.voltha.openolt.OpenoltGrpc.OpenoltImplBase;
import org.opencord.voltha.openolt.VolthaOpenOLT;
import org.opencord.voltha.openolt.VolthaOpenOLT.Indication;
import org.opencord.voltha.openolt.VolthaOpenOLT.PacketIndication;
import org.opencord.voltha.openolt.VolthaOpenOLT.Empty;
import org.opencord.voltha.openolt.VolthaOpenOLT.DeviceInfo.DeviceResourceRanges;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class DhcpGrpcServer extends OpenoltImplBase {
    private static final Logger logger = LoggerFactory.getLogger(DhcpGrpcServer.class);

    @Inject
    DeviceService deviceService;

    @Inject
    @ManagedExecutorConfig(maxAsync = 100)
    ManagedExecutor managedExecutor;

    // Configuration Properties
    @ConfigProperty(name = "dhcp.vlan.default.priority", defaultValue = "3")
    byte defaultVlanPriority;

    @ConfigProperty(name = "dhcp.lease.default.time", defaultValue = "86400")
    long defaultLeaseTime;

    @ConfigProperty(name = "dhcp.server.mac", defaultValue = "aa:bb:cc:dd:ee:ff")
    String serverMacString;

    @ConfigProperty(name = "dhcp.broadcast.mac", defaultValue = "ff:ff:ff:ff:ff:ff")
    String broadcastMacString;

    @ConfigProperty(name = "dhcp.pon.port.start", defaultValue = "0")
    int ponPortStart;

    @ConfigProperty(name = "dhcp.pon.port.count", defaultValue = "16")
    int ponPortCount;

    @ConfigProperty(name = "dhcp.onu.port.start", defaultValue = "0")
    int onuPortStart;

    @ConfigProperty(name = "dhcp.onu.port.count", defaultValue = "128")
    int onuPortCount;

    @ConfigProperty(name = "dhcp.uni.port.start", defaultValue = "0")
    int uniPortStart;

    @ConfigProperty(name = "dhcp.uni.port.count", defaultValue = "1")
    int uniPortCount;

    // Lazy-initialized MAC addresses
    private byte[] serverMac;
    private byte[] broadcastMac;

    private final Set<StreamObserver<Indication>> clientStreams = ConcurrentHashMap.newKeySet();

    private volatile boolean stormInProgress = false;
    private final Object stormLock = new Object();
    private CompletableFuture<Void> currentStormFuture = null;

    // Add these private fields at the class level (with other fields)
    private final Random heartbeatRandom = new Random();
    private final int heartbeatSignature = heartbeatRandom.nextInt();

    // Device info fields - initialized once at startup
    private final String deviceVendor = "Argela";
    private final String deviceModel = "DHCP-SIM-" + heartbeatRandom.nextInt(1000);
    private final String hardwareVersion = "1." + heartbeatRandom.nextInt(10);
    private final String firmwareVersion = "2." + heartbeatRandom.nextInt(100) + "." + heartbeatRandom.nextInt(100);
    private final String deviceId = "DHCP-DEV-" + System.currentTimeMillis();
    private final String deviceSerialNumber = "SN" + heartbeatRandom.nextInt(999999999);
    private final int groupIdStart = 1000 + heartbeatRandom.nextInt(1000);
    private final int groupIdEnd = groupIdStart + 100;
    private boolean pgwPreviouslyConnected = false;

    // Initialize MAC addresses from configuration
    private void initializeMacAddresses() {
        if (serverMac == null) {
            serverMac = macStringToBytes(serverMacString);
        }
        if (broadcastMac == null) {
            broadcastMac = macStringToBytes(broadcastMacString);
        }
    }

    // DHCP Message Type constants
    public static final byte DHCP_DISCOVER = 1;
    public static final byte DHCP_OFFER = 2;
    public static final byte DHCP_REQUEST = 3;
    public static final byte DHCP_ACK = 5;

    @Override
    public void enablePacketIndication(Empty request, StreamObserver<Indication> responseObserver) {
        clientStreams.add(responseObserver);
        pgwPreviouslyConnected = true;
        logger.info("Client connected to PacketIndication stream.");
    }

    @Override
    public void onuPacketOut(VolthaOpenOLT.OnuPacket request, StreamObserver<Empty> responseObserver) {
        CompletableFuture.runAsync(() -> processOnuPacket(request), managedExecutor)
                .exceptionally(throwable -> {
                    logger.error("Error processing Onu packet: {}", throwable.getMessage(), throwable);
                    return null;
                });

        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void uplinkPacketOut(VolthaOpenOLT.UplinkPacket request, StreamObserver<Empty> responseObserver) {
        CompletableFuture.runAsync(() -> processUplinkPacket(request), managedExecutor)
                .exceptionally(throwable -> {
                    logger.error("Error processing Uplink packet: {}", throwable.getMessage(), throwable);
                    return null;
                });

        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void heartbeatCheck(Empty request, StreamObserver<VolthaOpenOLT.Heartbeat> responseObserver) {
        VolthaOpenOLT.Heartbeat heartbeat = VolthaOpenOLT.Heartbeat.newBuilder()
                .setHeartbeatSignature(heartbeatSignature)
                .build();

        responseObserver.onNext(heartbeat);
        responseObserver.onCompleted();
    }

    @Override
    public void getDeviceInfo(Empty request, StreamObserver<VolthaOpenOLT.DeviceInfo> responseObserver) {
        DeviceResourceRanges.Pool onuIdPool =
                DeviceResourceRanges.Pool.newBuilder()
                        .setType(DeviceResourceRanges.Pool.PoolType.ONU_ID)
                        .setSharing(DeviceResourceRanges.Pool.SharingType.DEDICATED_PER_INTF)
                        .setStart(1)
                        .setEnd(128)
                        .build();

        DeviceResourceRanges.Pool allocIdPool =
                DeviceResourceRanges.Pool.newBuilder()
                        .setType(DeviceResourceRanges.Pool.PoolType.ALLOC_ID)
                        .setSharing(DeviceResourceRanges.Pool.SharingType.SHARED_BY_ALL_INTF_SAME_TECH)
                        .setStart(1024)
                        .setEnd(16383)
                        .build();

        DeviceResourceRanges.Pool gemportIdPool =
                DeviceResourceRanges.Pool.newBuilder()
                        .setType(DeviceResourceRanges.Pool.PoolType.GEMPORT_ID)
                        .setSharing(DeviceResourceRanges.Pool.SharingType.SHARED_BY_ALL_INTF_SAME_TECH)
                        .setStart(1024)
                        .setEnd(65535)
                        .build();

        DeviceResourceRanges.Pool flowIdPool =
                DeviceResourceRanges.Pool.newBuilder()
                        .setType(DeviceResourceRanges.Pool.PoolType.FLOW_ID)
                        .setSharing(DeviceResourceRanges.Pool.SharingType.SHARED_BY_ALL_INTF_ALL_TECH)
                        .setStart(1)
                        .setEnd(16383)
                        .build();

        DeviceResourceRanges ranges =
                DeviceResourceRanges.newBuilder()
                        .addIntfIds(0) // PON interface 0
                        .setTechnology(VolthaOpenOLT.PONTechnology.GPON)
                        .addPools(onuIdPool)
                        .addPools(allocIdPool)
                        .addPools(gemportIdPool)
                        .addPools(flowIdPool)
                        .build();

        VolthaOpenOLT.DeviceInfo deviceInfo = VolthaOpenOLT.DeviceInfo.newBuilder()
                .setVendor(deviceVendor)
                .setModel(deviceModel)
                .setHardwareVersion(hardwareVersion)
                .setFirmwareVersion(firmwareVersion)
                .setDeviceId(deviceId)
                .setDeviceSerialNumber(deviceSerialNumber)
                .setPreviouslyConnected(false)
                .setIgmpcaPreviouslyConnected(false)
                .setPgwPreviouslyConnected(pgwPreviouslyConnected)
                .setOmciPreviouslyConnected(false)
                .setGroupIdStart(groupIdStart)
                .setGroupIdEnd(groupIdEnd)
                .setPonPorts(ponPortCount)
                .setNniPorts(1)
                .setTechnology("GPON")
                .setSlotNumber(0)
                .addRanges(ranges)
                .build();

        responseObserver.onNext(deviceInfo);
        responseObserver.onCompleted();
    }

    /**
     * Processes incoming ONU packet from VOLTHA
     * @param request The ONU packet from VOLTHA containing DHCP messages
     */
    private void processOnuPacket(VolthaOpenOLT.OnuPacket request) {
        /*
        try{
            Thread.sleep(1000);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
         */

        byte[] packetData = request.getPkt().toByteArray();
        try {
            Ethernet ethFrame = Ethernet.deserializer().deserialize(packetData, 0, packetData.length);
            IPacket payload = ethFrame.getPayload();

            if (payload instanceof IPv4 ipv4Packet) {
                if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_UDP) {
                    UDP udpPacket = (UDP) ipv4Packet.getPayload();
                    if (isDhcpPacket(udpPacket)) {
                        DHCP dhcpPacket = (DHCP) udpPacket.getPayload();

                        // Find DHCP message type
                        byte messageType = getDhcpMessageType(dhcpPacket);
                        int xid = dhcpPacket.getTransactionId();

                        // Find device by XID
                        Optional<DeviceInfo> deviceOpt = deviceService.findDeviceByXid(xid);

                        if (deviceOpt.isPresent()) {
                            DeviceInfo device = deviceOpt.get();

                            switch (messageType) {
                                case DHCP_OFFER:
                                    handleReceivedOffer(device, dhcpPacket, request);
                                    break;
                                case DHCP_ACK:
                                    handleReceivedAck(device, dhcpPacket, request);
                                    break;
                                default:
                                    logger.debug("Unexpected DHCP message type in onuPacketOut: {}", messageType);
                            }
                        } else {
                            logger.warn("Device not found for XID: {}", xid);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error analyzing DHCP packet in onuPacketOut: {}", e.getMessage(), e);
        }
    }

    /**
     * Processes incoming uplink packet from VOLTHA
     * @param request The uplink packet from VOLTHA containing DHCP messages
     */
    private void processUplinkPacket(VolthaOpenOLT.UplinkPacket request) {
        /*
        try{
            Thread.sleep(1000);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
         */

        byte[] packetData = request.getPkt().toByteArray();
        try {
            Ethernet ethFrame = Ethernet.deserializer().deserialize(packetData, 0, packetData.length);
            IPacket payload = ethFrame.getPayload();

            if (payload instanceof IPv4 ipv4Packet) {
                if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_UDP) {
                    UDP udpPacket = (UDP) ipv4Packet.getPayload();
                    if (isDhcpPacket(udpPacket)) {
                        DHCP dhcpPacket = (DHCP) udpPacket.getPayload();

                        // Find DHCP message type
                        byte messageType = getDhcpMessageType(dhcpPacket);
                        int xid = dhcpPacket.getTransactionId();

                        // Find device by XID
                        Optional<DeviceInfo> deviceOpt = deviceService.findDeviceByXid(xid);

                        if (deviceOpt.isPresent()) {
                            DeviceInfo device = deviceOpt.get();

                            switch (messageType) {
                                case DHCP_DISCOVER:
                                    handleReceivedDiscovery(device, dhcpPacket, request);
                                    break;
                                case DHCP_REQUEST:
                                    handleReceivedRequest(device, dhcpPacket, request);
                                    break;
                                default:
                                    logger.debug("Unexpected DHCP message type in uplinkPacketOut: {}", messageType);
                            }
                        } else {
                            logger.warn("Device not found for XID: {}", xid);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error analyzing DHCP packet in uplinkPacketOut: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if UDP packet is a DHCP packet
     * @param udpPacket The UDP packet to check
     * @return true if packet is DHCP, false otherwise
     */
    private boolean isDhcpPacket(UDP udpPacket) {
        return udpPacket.getSourcePort() == UDP.DHCP_SERVER_PORT ||
                udpPacket.getSourcePort() == UDP.DHCP_CLIENT_PORT ||
                udpPacket.getDestinationPort() == UDP.DHCP_SERVER_PORT ||
                udpPacket.getDestinationPort() == UDP.DHCP_CLIENT_PORT;
    }

    /**
     * Extracts DHCP message type from DHCP packet
     * @param dhcpPacket The DHCP packet to analyze
     * @return DHCP message type (1-8) or 0 if unknown
     */
    private byte getDhcpMessageType(DHCP dhcpPacket) {
        List<DhcpOption> options = dhcpPacket.getOptions();
        if (options != null) {
            for (DhcpOption option : options) {
                if (option.getCode() == 53 && option.getLength() == 1) { // Message Type option
                    return option.getData()[0];
                }
            }
        }
        return 0; // Unknown type
    }

    /**
     * Handles received DHCP Discovery and sends Offer
     * @param device The device that sent the discovery
     * @param dhcpPacket The received DHCP packet
     * @param request The original uplink packet request
     */
    private void handleReceivedDiscovery(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.UplinkPacket request) {
        // Get network configuration by VLAN ID
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());

        // Assign IP
        String assignedIP = deviceService.generateUniqueIPAddress(device.getVlanId());
        device.setIpAddress(assignedIP);
        device.setState("OFFERING");

        // Assign network configuration
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());
        device.setLeaseTime(defaultLeaseTime);

        deviceService.updateDevice(device);
        sendDhcpOffer(device);
    }

    /**
     * Handles received DHCP Request and sends ACK
     * @param device The device that sent the request
     * @param dhcpPacket The received DHCP packet
     * @param request The original uplink packet request
     */
    private void handleReceivedRequest(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.UplinkPacket request) {
        // Get requested IP from Request (Option 50)
        String requestedIP = getRequestedIP(dhcpPacket);
        if (requestedIP != null) {
            device.setRequiredIp(requestedIP);
            device.setIpAddress(requestedIP); // Confirm same IP in ACK
        }

        // Update network configuration (by VLAN)
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());

        // Set device to ACKNOWLEDGING state
        device.setState("ACKNOWLEDGING");
        device.setLeaseStartTime(Instant.now());

        deviceService.updateDevice(device);
        sendDhcpAck(device);
    }

    /**
     * Handles received DHCP Offer and sends Request
     * @param device The device that received the offer
     * @param dhcpPacket The received DHCP packet
     * @param request The original ONU packet request
     */
    private void handleReceivedOffer(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.OnuPacket request) {
        // Get offered IP from Offer
        int offeredIP = dhcpPacket.getYourIPAddress();
        String offeredIPStr = IPv4.fromIPv4Address(offeredIP);

        device.setIpAddress(offeredIPStr);
        device.setRequiredIp(offeredIPStr);
        device.setState("REQUESTING");

        // Update network configuration (by VLAN)
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());

        deviceService.updateDevice(device);
        sendDhcpRequest(device);
    }

    /**
     * Handles received DHCP ACK and completes the process
     * @param device The device that received the ACK
     * @param dhcpPacket The received DHCP packet
     * @param request The original ONU packet request
     */
    private void handleReceivedAck(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.OnuPacket request) {
        // Get confirmed IP from ACK
        int confirmedIP = dhcpPacket.getYourIPAddress();
        String confirmedIPStr = IPv4.fromIPv4Address(confirmedIP);

        device.setIpAddress(confirmedIPStr);
        device.setState("ACKNOWLEDGED");
        device.setDhcpCompletionTime(Instant.now());
        device.setLeaseStartTime(Instant.now());

        // Update network configuration (by VLAN)
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());

        deviceService.updateDevice(device);
    }

    /**
     * Extracts requested IP address from DHCP Request packet (Option 50)
     * @param dhcpPacket The DHCP packet to analyze
     * @return Requested IP address as string, or null if not found
     */
    private String getRequestedIP(DHCP dhcpPacket) {
        List<DhcpOption> options = dhcpPacket.getOptions();
        if (options != null) {
            for (DhcpOption option : options) {
                if (option.getCode() == 50 && option.getLength() == 4) { // Requested IP Address
                    byte[] ipBytes = option.getData();
                    return String.format("%d.%d.%d.%d",
                            ipBytes[0] & 0xFF,
                            ipBytes[1] & 0xFF,
                            ipBytes[2] & 0xFF,
                            ipBytes[3] & 0xFF);
                }
            }
        }
        return null;
    }

    /**
     * Creates DHCP Server packets
     * @param cTag VLAN tag value
     * @param clientMac Client MAC address
     * @param destinationMac Destination MAC address
     * @param sourceMac Source MAC address
     * @param messageType DHCP message type (DISCOVERY, OFFER, REQUEST or ACK)
     * @param clientIP IP address to be given to client
     * @param offeredIP Offered IP for request (null for Discover)
     * @param serverIP DHCP server IP address
     * @param gatewayIP Gateway IP address
     * @param subnetMask Subnet mask
     * @param dnsServers DNS servers
     * @param leaseTime Lease time in seconds
     * @param XID Transaction ID
     * @param sourceAddress Source IP address
     * @param destinationAddress Destination IP address
     * @return DHCP packet as byte array
     */
    private byte[] createDhcpPacket(int cTag, byte[] clientMac, byte[] destinationMac, byte[] sourceMac,
                                    byte messageType, String clientIP, String offeredIP, String serverIP,
                                    String gatewayIP, String subnetMask, String[] dnsServers, int leaseTime,
                                    int XID, String sourceAddress, String destinationAddress) {

        initializeMacAddresses();

        DHCP dhcpPacket = new DHCP();
        dhcpPacket.setOpCode((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? DHCP.OPCODE_REQUEST : DHCP.OPCODE_REPLY);
        dhcpPacket.setHardwareType(DHCP.HWTYPE_ETHERNET);
        dhcpPacket.setHardwareAddressLength((byte) 6);
        dhcpPacket.setHops((byte) 0);
        dhcpPacket.setTransactionId(XID);
        dhcpPacket.setSeconds((short) 0);
        dhcpPacket.setFlags((short) 0x8000);
        dhcpPacket.setClientIPAddress(0);
        dhcpPacket.setYourIPAddress(IPv4.toIPv4Address(clientIP));
        dhcpPacket.setServerIPAddress(IPv4.toIPv4Address(serverIP));
        dhcpPacket.setGatewayIPAddress(0);
        dhcpPacket.setClientHardwareAddress(clientMac);

        // Create DHCP Options
        List<DhcpOption> options = new ArrayList<>();

        // Option 53: DHCP Message Type
        DhcpOption opt53 = new DhcpOption()
                .setCode((byte) 53)
                .setLength((byte) 1)
                .setData(new byte[]{messageType});
        options.add(opt53);

        if (messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) {
            //  01	Subnet Mask
            //  02	Time Offset
            //  03	Router
            //  06  Domain Name Server (DNS)
            //  12	Host Name
            //	15	Domain Name
            //  18	Interface MTU
            //	26	Broadcast Address Mask
            //  28	Broadcast Address
            //	42	NIS+ Domain Name
            //	44	NetBIOS over TCP/IP node type
            //	47	NetBIOS Scope
            //  119	Domain Search
            //	121	Classless Static Route
            DhcpOption opt55 = new DhcpOption() //Option 55: Parameter Request List
                    .setCode((byte) 55)
                    .setLength((byte) 5)
                    .setData(new byte[]{1, 3, 6, 15, 18});
            options.add(opt55);

            if (messageType == DHCP_REQUEST) {
                // Option 50: Requested IP Address
                if (offeredIP != null) {
                    byte[] ipBytes = IPv4.toIPv4AddressBytes(offeredIP);
                    DhcpOption opt50 = new DhcpOption()
                            .setCode((byte) 50)
                            .setLength((byte) 4)
                            .setData(ipBytes);
                    options.add(opt50);
                }
                // Option 54: Server Identifier
                if (!serverIP.equals("0.0.0.0")) {
                    byte[] serverBytes = IPv4.toIPv4AddressBytes(serverIP);
                    DhcpOption opt54 = new DhcpOption()
                            .setCode((byte) 54)
                            .setLength((byte) 4)
                            .setData(serverBytes);
                    options.add(opt54);
                }
            }
        } else {
            // Option 1: Subnet Mask
            if (subnetMask != null) {
                byte[] subnetBytes = IPv4.toIPv4AddressBytes(subnetMask);
                DhcpOption opt1 = new DhcpOption()
                        .setCode((byte) 1)
                        .setLength((byte) 4)
                        .setData(subnetBytes);
                options.add(opt1);
            }

            // Option 3: Router (Gateway)
            if (gatewayIP != null) {
                byte[] gatewayBytes = IPv4.toIPv4AddressBytes(gatewayIP);
                DhcpOption opt3 = new DhcpOption()
                        .setCode((byte) 3)
                        .setLength((byte) 4)
                        .setData(gatewayBytes);
                options.add(opt3);
            }

            // Option 6: DNS Servers
            if (dnsServers != null && dnsServers.length > 0) {
                byte[] dnsBytes = new byte[dnsServers.length * 4];
                for (int i = 0; i < dnsServers.length; i++) {
                    byte[] dns = IPv4.toIPv4AddressBytes(dnsServers[i]);
                    System.arraycopy(dns, 0, dnsBytes, i * 4, 4);
                }
                DhcpOption opt6 = new DhcpOption()
                        .setCode((byte) 6)
                        .setLength((byte) dnsBytes.length)
                        .setData(dnsBytes);
                options.add(opt6);
            }

            // Option 51: IP Address Lease Time
            byte[] leaseTimeBytes = new byte[4];
            leaseTimeBytes[0] = (byte) (leaseTime >>> 24);
            leaseTimeBytes[1] = (byte) (leaseTime >>> 16);
            leaseTimeBytes[2] = (byte) (leaseTime >>> 8);
            leaseTimeBytes[3] = (byte) leaseTime;
            DhcpOption opt51 = new DhcpOption()
                    .setCode((byte) 51)
                    .setLength((byte) 4)
                    .setData(leaseTimeBytes);
            options.add(opt51);

            // Option 54: Server Identifier
            byte[] serverBytes = IPv4.toIPv4AddressBytes(serverIP);
            DhcpOption opt54 = new DhcpOption()
                    .setCode((byte) 54)
                    .setLength((byte) 4)
                    .setData(serverBytes);
            options.add(opt54);
        }

        // Option 82: Relay Agent Information Option
        DhcpRelayAgentOption relayOpt = new DhcpRelayAgentOption();
        relayOpt.setCode((byte) 82); // Option 82
        DhcpOption circuitId = new DhcpOption()
                .setCode((byte) 1)
                .setLength((byte) 4)
                .setData(new byte[]{0x01, 0x02, 0x03, 0x04});
        relayOpt.addSubOption(circuitId);
        options.add(relayOpt);

        // Option 255: End Option
        DhcpOption endOption = new DhcpOption()
                .setCode((byte) 255);
        options.add(endOption);

        dhcpPacket.setOptions(options);

        // UDP layer
        UDP udp = new UDP();
        dhcpPacket.setOpCode((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? DHCP.OPCODE_REQUEST : DHCP.OPCODE_REPLY);
        udp.setSourcePort((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? UDP.DHCP_CLIENT_PORT : UDP.DHCP_SERVER_PORT);
        udp.setDestinationPort((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? UDP.DHCP_SERVER_PORT : UDP.DHCP_CLIENT_PORT);
        udp.setPayload(dhcpPacket);

        // IPv4 layer
        IPv4 ipv4 = new IPv4();
        ipv4.setVersion((byte) 4);
        ipv4.setTtl((byte) 64);
        ipv4.setIdentification((short) 0x1234);
        ipv4.setFlags((byte) 0x4000);
        ipv4.setProtocol(IPv4.PROTOCOL_UDP);
        ipv4.setSourceAddress(sourceAddress);
        ipv4.setDestinationAddress(destinationAddress);
        ipv4.setPayload(udp);

        // Ethernet layer
        Ethernet eth = new Ethernet();
        eth.setEtherType(Ethernet.TYPE_IPV4);
        eth.setSourceMACAddress(sourceMac);
        eth.setDestinationMACAddress(destinationMac);
        eth.setVlanID((byte) cTag);
        eth.setPriorityCode(defaultVlanPriority);
        eth.setPayload(ipv4);

        return eth.serialize();
    }

    /**
     * Sends DHCP packet based on simulation request
     * @param request The DHCP simulation request containing packet type and parameters
     */
    public void sendDhcp(DhcpSimulationRequest request){
        String packetType = request.getPacketType().toLowerCase();
        // Try to find existing idle device first
        Optional<DeviceInfo> existingDevice = deviceService.findIdleDevice(
                request.getPonPort(),
                request.getOnuId(),
                request.getUniId(),
                request.getGemPort(),
                request.getCTag()
        );

        DeviceInfo device;

        if (existingDevice.isPresent()) {
            device = existingDevice.get();
            logger.info("Using existing idle device ID={} for {} request",
                    device.getId(), packetType.toUpperCase());

            // Update device based on packet type
            switch(packetType) {
                case "discovery" -> updateDeviceForDiscovery(device);
                case "offer" -> updateDeviceForOffer(device);
                case "request" -> updateDeviceForRequest(device);
                case "ack" -> updateDeviceForAck(device);
                default -> {
                    logger.error("Unknown packet type: {}", request.getPacketType());
                    return;
                }
            }

            // Override MAC if provided in request
            if (request.getClientMac() != null && !request.getClientMac().trim().isEmpty()) {
                device.setClientMac(request.getClientMac().trim());
            }

            deviceService.updateDevice(device);

        } else {
            // Create new device if no matching idle device found
            logger.info("No matching idle device found, creating new device for {} request",
                    packetType.toUpperCase());

            device = switch(packetType) {
                case "discovery" -> createDeviceForDiscovery(request);
                case "offer" -> createDeviceForOffer(request);
                case "request" -> createDeviceForRequest(request);
                case "ack" -> createDeviceForAck(request);
                default -> null;
            };

            if (device != null) {
                deviceService.addDevice(device);
            } else {
                logger.error("Unknown packet type: {}", request.getPacketType());
                return;
            }
        }

        // Send DHCP packet
        switch(packetType) {
            case "discovery" -> sendDhcpDiscover(device);
            case "offer" -> sendDhcpOffer(device);
            case "request" -> sendDhcpRequest(device);
            case "ack" -> sendDhcpAck(device);
        }
    }

    private void updateDeviceForDiscovery(DeviceInfo device) {
        device.setState("DISCOVERING");
        device.setIpAddress(null);
        device.setRequiredIp(null);
        device.setDns(null);
        device.setGateway(null);
        device.setServerIdentifier(null);
        device.setSubnetMask(null);
        device.setLeaseTime(0);
        device.setLeaseStartTime(null);
        device.setDhcpStartTime(Instant.now());
        device.setDhcpCompletionTime(null);
    }

    private void updateDeviceForOffer(DeviceInfo device) {
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());

        device.setState("OFFERED");
        device.setIpAddress(deviceService.generateUniqueIPAddress(device.getVlanId()));
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());
        device.setLeaseTime(defaultLeaseTime);
        device.setDhcpStartTime(Instant.now());
    }

    private void updateDeviceForRequest(DeviceInfo device) {
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        String offeredIP = deviceService.generateUniqueIPAddress(device.getVlanId());

        device.setState("REQUESTING");
        device.setIpAddress(offeredIP);
        device.setRequiredIp(offeredIP);
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());
        device.setLeaseTime(defaultLeaseTime);
        device.setDhcpStartTime(Instant.now());
    }

    private void updateDeviceForAck(DeviceInfo device) {
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        String requestedIP = deviceService.generateUniqueIPAddress(device.getVlanId());

        device.setState("ACKNOWLEDGED");
        device.setIpAddress(requestedIP);
        device.setRequiredIp(requestedIP);
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());
        device.setLeaseTime(defaultLeaseTime);
        device.setLeaseStartTime(Instant.now());
        device.setDhcpStartTime(Instant.now());
        device.setDhcpCompletionTime(Instant.now());
    }

    /**
     * Creates new device for Discovery
     * @param request The DHCP simulation request
     * @return DeviceInfo object configured for discovery state
     */
    private DeviceInfo createDeviceForDiscovery(DhcpSimulationRequest request) {
        DeviceInfo device = new DeviceInfo(
                0,                           // id (will be auto-assigned by DeviceService)
                null,                           // clientMac (DeviceService will auto-assign)
                null,                           // ipAddress (not yet available)
                null,                           // requiredIp (not yet available)
                "DISCOVERING",                  // state
                null,                           // dns (not yet available - during discovery phase)
                null,                           // gateway (not yet available)
                null,                           // serverIdentifier (not yet available)
                null,                           // subnetMask (not available during discovery)
                0,                              // xid (DeviceService will auto-assign)
                0,                              // leaseTime (not yet available)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );

        if (request.getClientMac() != null && !request.getClientMac().trim().isEmpty()) {
            device.setClientMac(request.getClientMac().trim());
        }

        return device;
    }

    /**
     * Creates new device for Offer
     * @param request The DHCP simulation request
     * @return DeviceInfo object configured for offer state
     */
    private DeviceInfo createDeviceForOffer(DhcpSimulationRequest request) {
        // Get network configuration
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());

        DeviceInfo device = new DeviceInfo(
                0,                           // id (will be auto-assigned by DeviceService)
                null,                           // clientMac
                deviceService.generateUniqueIPAddress(request.getCTag()), // ipAddress (based on VLAN)
                null,                           // requiredIp (not yet available)
                "OFFERED",                      // state
                networkConfig.getDnsServers(),  // dns (based on VLAN)
                networkConfig.getGateway(),     // gateway (based on VLAN)
                networkConfig.getServerIdentifier(), // serverIdentifier (based on VLAN)
                networkConfig.getSubnetMask(),  // subnetMask (based on VLAN)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 hours)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );

        if (request.getClientMac() != null && !request.getClientMac().trim().isEmpty()) {
            device.setClientMac(request.getClientMac().trim());
        }

        return device;
    }

    /**
     * Creates new device for Request
     * @param request The DHCP simulation request
     * @return DeviceInfo object configured for request state
     */
    private DeviceInfo createDeviceForRequest(DhcpSimulationRequest request) {
        // Get network configuration
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());
        String offeredIP = deviceService.generateUniqueIPAddress(request.getCTag());

        DeviceInfo device = new DeviceInfo(
                0,                           // id (will be auto-assigned by DeviceService)
                null,                           // clientMac
                offeredIP,                      // ipAddress (IP received in offer - based on VLAN)
                offeredIP,                      // requiredIp (IP to be requested)
                "REQUESTING",                   // state
                networkConfig.getDnsServers(),  // dns (based on VLAN)
                networkConfig.getGateway(),     // gateway (based on VLAN)
                networkConfig.getServerIdentifier(), // serverIdentifier (based on VLAN)
                networkConfig.getSubnetMask(),  // subnetMask (based on VLAN)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 hours)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );

        if (request.getClientMac() != null && !request.getClientMac().trim().isEmpty()) {
            device.setClientMac(request.getClientMac().trim());
        }

        return device;
    }

    /**
     * Creates new device for ACK
     * @param request The DHCP simulation request
     * @return DeviceInfo object configured for acknowledged state
     */
    private DeviceInfo createDeviceForAck(DhcpSimulationRequest request) {
        // Get network configuration
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());
        String requestedIP = deviceService.generateUniqueIPAddress(request.getCTag());

        DeviceInfo device = new DeviceInfo(
                0,                           // id (will be auto-assigned by DeviceService)
                null,                           // clientMac
                requestedIP,                    // ipAddress (IP to be acknowledged - based on VLAN)
                requestedIP,                    // requiredIp (requested IP)
                "ACKNOWLEDGED",                 // state (will be bound with ACK)
                networkConfig.getDnsServers(),  // dns (based on VLAN)
                networkConfig.getGateway(),     // gateway (based on VLAN)
                networkConfig.getServerIdentifier(), // serverIdentifier (based on VLAN)
                networkConfig.getSubnetMask(),  // subnetMask (based on VLAN)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 hours)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );

        if (request.getClientMac() != null && !request.getClientMac().trim().isEmpty()) {
            device.setClientMac(request.getClientMac().trim());
        }

        return device;
    }

    /**
     * Sends DHCP Discover packet
     * @param device The device that will send the discovery
     */
    public void sendDhcpDiscover(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        // Create DHCP Discovery packet
        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),            // VLAN tag
                clientMac,                     // Client MAC
                broadcastMac,                  // Destination MAC (broadcast)
                clientMac,                     // Source MAC
                DHCP_DISCOVER,                 // Message type
                "0.0.0.0",                     // Client IP (0.0.0.0 in discovery)
                null,                          // Offered IP (null in discovery)
                "0.0.0.0",                     // Server IP (not known yet)
                "0.0.0.0",                     // Gateway IP (not known yet)
                null,                          // Subnet mask (null in discovery)
                null,                          // DNS servers (null in discovery)
                0,                             // Lease time (0 in discovery)
                device.getXid(),               // Transaction ID
                "0.0.0.0",                     // Source IP address
                "255.255.255.255"              // Destination IP (broadcast)
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * Sends DHCP Offer packet
     * @param device The device to send the offer to
     */
    public void sendDhcpOffer(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        String[] dnsServers = null;
        if (device.getDns() != null && !device.getDns().isEmpty()) {
            dnsServers = device.getDns().split(",");
            // Clean whitespaces
            for (int i = 0; i < dnsServers.length; i++) {
                dnsServers[i] = dnsServers[i].trim();
            }
        }

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                clientMac,                      // Destination MAC (sent to client)
                serverMac,                      // Source MAC (server)
                DHCP_OFFER,                     // Message type
                device.getIpAddress(),          // Client IP (offered IP)
                null,                           // Offered IP (not used in offer)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                dnsServers,                     // DNS servers
                (int)device.getLeaseTime(),     // Lease time
                device.getXid(),                // Transaction ID
                device.getServerIdentifier(),   // Source IP address
                device.getIpAddress()           // Destination IP
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * Sends DHCP Request packet
     * @param device The device that will send the request
     */
    public void sendDhcpRequest(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                broadcastMac,                   // Destination MAC (broadcast)
                clientMac,                      // Source MAC (client)
                DHCP_REQUEST,                   // Message type
                "0.0.0.0",                      // Client IP (usually 0.0.0.0 in request)
                device.getRequiredIp(),         // Offered IP (requested IP)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                null,                           // DNS servers (not sent in request)
                0,                              // Lease time (not sent in request)
                device.getXid(),                // Transaction ID
                "0.0.0.0",                      // Source IP address
                "255.255.255.255"               // Destination IP (broadcast)
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * Sends DHCP ACK packet
     * @param device The device to send the ACK to
     */
    public void sendDhcpAck(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        String[] dnsServers = null;
        if (device.getDns() != null && !device.getDns().isEmpty()) {
            dnsServers = device.getDns().split(",");
            for (int i = 0; i < dnsServers.length; i++) {
                dnsServers[i] = dnsServers[i].trim();
            }
        }

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                clientMac,                      // Destination MAC (sent to client)
                serverMac,                      // Source MAC (server)
                DHCP_ACK,                       // Message type
                device.getIpAddress(),          // Client IP (acknowledged IP)
                null,                           // Offered IP (not used in ACK)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                dnsServers,                     // DNS servers
                (int)device.getLeaseTime(),     // Lease time
                device.getXid(),                // Transaction ID
                device.getServerIdentifier(),   // Source IP address
                device.getIpAddress()           // Destination IP
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * Converts MAC string to byte array
     * @param macString MAC address in string format (xx:xx:xx:xx:xx:xx)
     * @return MAC address as byte array
     */
    private byte[] macStringToBytes(String macString) {
        String[] parts = macString.split(":");
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            mac[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return mac;
    }

    /**
     * Sends packet indication to connected clients
     * @param device The device information
     * @param dhcpPacket The DHCP packet data
     */
    private void sendPacketIndication(DeviceInfo device, byte[] dhcpPacket) {
        if (dhcpPacket == null || dhcpPacket.length == 0) {
            logger.error("Invalid DHCP packet data");
            return;
        }
        try {
            PacketIndication.Builder pktBuilder = PacketIndication.newBuilder()
                    .setIntfType("pon")
                    .setIntfId(device.getPonPort())
                    .setGemportId(device.getGemPort())
                    .setPkt(ByteString.copyFrom(dhcpPacket));

            // Add optional fields with control
            if (device.getOnuId() > 0) {
                pktBuilder.setOnuId(device.getOnuId());
            }
            if (device.getUniId() >= 0) {
                pktBuilder.setUniId(device.getUniId());
            }
            if (device.getPonPort() > 0) {
                pktBuilder.setPortNo(device.getPonPort());
            }

            pktBuilder.setFlowId(1000 + device.getVlanId());

            Indication indication = Indication.newBuilder()
                    .setPktInd(pktBuilder.build())
                    .build();

            synchronized (clientStreams) {
                clientStreams.removeIf(stream -> {
                    try {
                        stream.onNext(indication);
                        return false; // Stream is working
                    } catch (Exception e) {
                        logger.warn("Client stream error, removing: {}", e.getMessage());
                        try {
                            stream.onError(e);
                        } catch (Exception ignored) {}
                        return true; // Remove stream
                    }
                });
            }

        } catch (Exception e) {
            logger.error("Error creating packet indication: {}", e.getMessage(), e);
        }
    }

    /**
     * Simulates DHCP storm by creating multiple devices rapidly
     * @param rate Number of devices per second (if provided)
     * @param intervalSec Interval between devices in seconds (if provided)
     */
    public void simulateDhcpStorm(Integer rate, Double intervalSec) {
        logger.info("Starting DHCP storm simulation with rate: {}, intervalSec: {}", rate, intervalSec);

        synchronized (stormLock) {
            if (stormInProgress) {
                throw new RuntimeException("DHCP storm is already in progress. Please wait for current storm to complete.");
            }
            stormInProgress = true;
        }

        DeviceWebSocket.broadcastStormStatus("progress", rate, intervalSec, "Storm started");

        // Calculate delay
        long delayMs;
        if (rate != null && rate > 0) {
            delayMs = 1000 / rate;
        } else if (intervalSec != null && intervalSec > 0) {
            delayMs = (long) (intervalSec * 1000);
        } else {
            synchronized (stormLock) {
                stormInProgress = false;
            }
            throw new IllegalArgumentException("Invalid parameters for DHCP storm simulation");
        }

        // Get idle devices for storm - use preloaded devices instead of creating new ones
        List<DeviceInfo> idleDevices = deviceService.getDevicesByState("IDLE");
        int totalDevices = idleDevices.size();

        currentStormFuture = CompletableFuture.runAsync(() -> {
            try {
                logger.info("DHCP Storm started - Rate: {}, Available idle devices: {}",
                        (rate != null ? rate + " devices/sec" : "1 device per " + intervalSec + " seconds"),
                        totalDevices);

                if (totalDevices == 0) {
                    logger.warn("No idle devices available for storm simulation");
                    DeviceWebSocket.broadcastStormStatus(
                            "error",
                            null,
                            null,
                            "No idle devices available for storm simulation"
                    );
                    return;
                }

                int successCount = 0;
                int failureCount = 0;

                // Process each idle device
                for (int deviceIndex = 0; deviceIndex < totalDevices; deviceIndex++) {
                    synchronized (stormLock) {
                        if (!stormInProgress) {
                            logger.debug("Storm cancelled at device {}", deviceIndex);
                            break;
                        }
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        logger.debug("Storm thread interrupted at device {}", deviceIndex);
                        break;
                    }

                    try {
                        DeviceInfo device = idleDevices.get(deviceIndex);

                        // Update device state and send discovery
                        updateDeviceForDiscovery(device);
                        deviceService.updateDevice(device);
                        sendDhcpDiscover(device);

                        successCount++;

                        if ((deviceIndex + 1) % 50 == 0) {
                            logger.info("Storm progress: {}/{} devices sent", deviceIndex + 1, totalDevices);
                        }

                        if (deviceIndex < totalDevices - 1) {
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        failureCount++;
                        logger.error("Error sending device {}: {}", deviceIndex, e.getMessage());
                    }
                }

                logger.info("DHCP Storm completed: {} devices sent successfully, {} failed out of {} total devices",
                        successCount, failureCount, totalDevices);

                DeviceWebSocket.broadcastStormStatus(
                        "ready",
                        null,
                        null,
                        "Storm completed successfully"
                );
            } catch (Exception e) {
                logger.error("Fatal error during DHCP storm: {}", e.getMessage(), e);
                DeviceWebSocket.broadcastStormStatus(
                        "error",
                        null,
                        null,
                        "Storm error: " + e.getMessage()
                );
            } finally {
                synchronized (stormLock) {
                    stormInProgress = false;
                    currentStormFuture = null;
                }
                logger.info("DHCP Storm session ended");
            }
        }, managedExecutor);

        // Handle future for exception handling
        currentStormFuture.exceptionally(throwable -> {
            logger.error("Storm execution error: {}", throwable.getMessage());
            DeviceWebSocket.broadcastStormStatus(
                    "error",
                    null,
                    null,
                    "Storm execution error: " + throwable.getMessage()
            );
            synchronized (stormLock) {
                stormInProgress = false;
                currentStormFuture = null;
            }
            return null;
        });
    }

    /**
     * Gets storm configuration information
     * @return String containing storm configuration details
     */
    public String getStormInfo() {
        int totalDevices = ponPortCount * onuPortCount;
        return "Storm Configuration - PON Ports: " + ponPortStart + "-" + (ponPortStart + ponPortCount - 1) +
                ", ONU Ports: " + onuPortStart + "-" + (onuPortStart + onuPortCount - 1) +
                ", Total Devices: " + totalDevices;
    }

    /**
     * Checks if storm is currently in progress
     * @return true if storm is running, false otherwise
     */
    public boolean isStormInProgress() {
        synchronized (stormLock) {
            return stormInProgress;
        }
    }

    /**
     * Cancels the currently running storm
     */
    public void cancelStorm() {
        synchronized (stormLock) {
            if (stormInProgress && currentStormFuture != null) {
                logger.info("Cancelling DHCP storm...");

                stormInProgress = false;

                boolean cancelled = currentStormFuture.cancel(true); // interrupt if running

                logger.info("DHCP storm cancellation result: {}", cancelled);

                // Reset state
                currentStormFuture = null;

                DeviceWebSocket.broadcastStormStatus(
                        "ready",
                        null,
                        null,
                        "Storm cancelled"
                );
            } else {
                logger.debug("No active storm to cancel (stormInProgress: {}, currentStormFuture: {})",
                        stormInProgress, (currentStormFuture != null));


                // Ensure consistent state
                stormInProgress = false;
                currentStormFuture = null;

                DeviceWebSocket.broadcastStormStatus(
                        "ready",
                        null,
                        null,
                        "No active storm"
                );
            }
        }
    }

    /**
     * Gets current storm status
     * @return String describing current storm status
     */
    public String getStormStatus() {
        synchronized (stormLock) {
            if (stormInProgress) {
                return "DHCP Storm is currently in progress";
            } else {
                return "No active DHCP Storm";
            }
        }
    }
}