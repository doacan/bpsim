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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@GrpcService
public class DhcpGrpcServer extends OpenoltImplBase {
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

    // Lazy-initialized MAC addresses
    private byte[] serverMac;
    private byte[] broadcastMac;

    private final Set<StreamObserver<Indication>> clientStreams = ConcurrentHashMap.newKeySet();

    private volatile boolean stormInProgress = false;
    private final Object stormLock = new Object();
    private CompletableFuture<Void> currentStormFuture = null;

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
        System.out.println("Client connected to PacketIndication stream.");
    }

    @Override
    public void onuPacketOut(VolthaOpenOLT.OnuPacket request, StreamObserver<Empty> responseObserver) {
        CompletableFuture.runAsync(() -> processOnuPacket(request), managedExecutor)
                .exceptionally(throwable -> {
                    System.err.println("Error processing Onu packet: " + throwable.getMessage());
                    return null;
                });

        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void uplinkPacketOut(VolthaOpenOLT.UplinkPacket request, StreamObserver<Empty> responseObserver) {
        CompletableFuture.runAsync(() -> processUplinkPacket(request), managedExecutor)
                .exceptionally(throwable -> {
                    System.err.println("Error processing Uplink packet: " + throwable.getMessage());
                    return null;
                });

        responseObserver.onNext(Empty.newBuilder().build());
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

                        // DHCP mesaj türünü bul
                        byte messageType = getDhcpMessageType(dhcpPacket);
                        int xid = dhcpPacket.getTransactionId();

                        // XID'ye göre cihazı bul
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
                                    System.out.println("Unexpected DHCP message type in onuPacketOut: " + messageType);
                            }
                        } else {
                            System.err.println("Device not found for XID: " + xid);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error analyzing DHCP packet in onuPacketOut: " + e.getMessage());
            e.printStackTrace();
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

                        // DHCP mesaj türünü bul
                        byte messageType = getDhcpMessageType(dhcpPacket);
                        int xid = dhcpPacket.getTransactionId();

                        // XID'ye göre cihazı bul
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
                                    System.out.println("Unexpected DHCP message type in uplinkPacketOut: " + messageType);
                            }
                        } else {
                            System.err.println("Device not found for XID: " + xid);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error analyzing DHCP packet in uplinkPacketOut: " + e.getMessage());
            e.printStackTrace();
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
        // VLAN ID'ye göre network konfigürasyonu al
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());

        // IP ata
        String assignedIP = deviceService.generateUniqueIPAddress(device.getVlanId());
        device.setIpAddress(assignedIP);
        device.setState("OFFERING");

        // Network konfigürasyonu ata
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
        // Request'teki istenen IP'yi al (Option 50)
        String requestedIP = getRequestedIP(dhcpPacket);
        if (requestedIP != null) {
            device.setRequiredIp(requestedIP);
            device.setIpAddress(requestedIP); // ACK'da aynı IP'yi onayla
        }

        // Network konfigürasyonunu güncelle (VLAN'a göre)
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());

        // Device'ı ACKNOWLEDGING durumuna getir
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
        // Offer'dan teklif edilen IP'yi al
        int offeredIP = dhcpPacket.getYourIPAddress();
        String offeredIPStr = IPv4.fromIPv4Address(offeredIP);

        device.setIpAddress(offeredIPStr);
        device.setRequiredIp(offeredIPStr);
        device.setState("REQUESTING");

        // Network konfigürasyonunu güncelle (VLAN'a göre)
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
        device.setState("ACKNOWLEDGED"); // IP address successfully assigned
        device.setLeaseStartTime(Instant.now());

        // Update network configuration (by VLAN)
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(device.getVlanId());
        device.setDns(networkConfig.getDnsServers());
        device.setGateway(networkConfig.getGateway());
        device.setServerIdentifier(networkConfig.getServerIdentifier());
        device.setSubnetMask(networkConfig.getSubnetMask());

        if (device.getDhcpStartTime() != null) {
            long dhcpDuration = device.getDhcpCompletionTimeMs();
        }

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
     * DHCP Server paketleri oluşturur
     * @param cTag VLAN tag değeri
     * @param clientMac Client MAC adresi
     * @param destinationMac Destination MAC adresi
     * @param sourceMac Source MAC adresi
     * @param messageType DHCP mesaj türü (DISCOVERY, OFFER, REQUEST veya ACK)
     * @param clientIP Client'a verilecek IP adresi
     * @param offeredIP Request için teklif edilen IP (Discover için null)
     * @param serverIP DHCP server IP adresi
     * @param gatewayIP Gateway IP adresi
     * @param subnetMask Subnet mask
     * @param dnsServers DNS sunucuları
     * @param leaseTime Lease süresi (saniye)
     * @return DHCP paketi byte array olarak
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

        // DHCP Options oluştur
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
                if (serverIP != null) {
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

        // UDP katmanı
        UDP udp = new UDP();
        dhcpPacket.setOpCode((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? DHCP.OPCODE_REQUEST : DHCP.OPCODE_REPLY);
        udp.setSourcePort((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? UDP.DHCP_CLIENT_PORT : UDP.DHCP_SERVER_PORT);
        udp.setDestinationPort((messageType == DHCP_DISCOVER || messageType == DHCP_REQUEST) ? UDP.DHCP_SERVER_PORT : UDP.DHCP_CLIENT_PORT);
        udp.setPayload(dhcpPacket);

        // IPv4 katmanı
        IPv4 ipv4 = new IPv4();
        ipv4.setVersion((byte) 4);
        ipv4.setTtl((byte) 64);
        ipv4.setIdentification((short) 0x1234);
        ipv4.setFlags((byte) 0x4000);
        ipv4.setProtocol(IPv4.PROTOCOL_UDP);
        ipv4.setSourceAddress(sourceAddress);
        ipv4.setDestinationAddress(destinationAddress);
        ipv4.setPayload(udp);

        // Ethernet katmanı
        Ethernet eth = new Ethernet();
        eth.setEtherType(Ethernet.TYPE_IPV4);
        eth.setSourceMACAddress(sourceMac);
        eth.setDestinationMACAddress(destinationMac);
        eth.setVlanID((byte) cTag);
        eth.setPriorityCode(defaultVlanPriority);
        eth.setPayload(ipv4);

        return eth.serialize();
    }

    public void sendDhcp(DhcpSimulationRequest request){
        String packetType = request.getPacketType().toLowerCase();
        DeviceInfo device = switch(packetType) {
            case "discovery" -> createDeviceForDiscovery(request);
            case "offer" -> createDeviceForOffer(request);
            case "request" -> createDeviceForRequest(request);
            case "ack" -> createDeviceForAck(request);
            default -> null;
        };

        if (device != null) {
            deviceService.addDevice(device);
            switch(packetType) {
                case "discovery" -> sendDhcpDiscover(device);
                case "offer" -> sendDhcpOffer(device);
                case "request" -> sendDhcpRequest(device);
                case "ack" -> sendDhcpAck(device);
            }
        } else {
            System.err.println("Unknown packet type: " + request.getPacketType());
        }
    }

    /**
     * Discovery için yeni cihaz oluşturur
     */
    private DeviceInfo createDeviceForDiscovery(DhcpSimulationRequest request) {
        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac (DeviceService otomatik atayacak)
                null,                           // ipAddress (henüz yok)
                null,                           // requiredIp (henüz yok)
                "DISCOVERING",                  // state
                null,                           // dns (henüz yok - discovery aşamasında)
                null,                           // gateway (henüz yok)
                null,                           // serverIdentifier (henüz yok)
                null,                           // subnetMask (henüz yok)
                0,                              // xid (DeviceService otomatik atayacak)
                0,                              // leaseTime (henüz yok)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );
    }

    /**
     * Offer için yeni cihaz oluşturur
     */
    private DeviceInfo createDeviceForOffer(DhcpSimulationRequest request) {
        // Network konfigürasyonunu al
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());

        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                deviceService.generateUniqueIPAddress(request.getCTag()), // ipAddress (VLAN'a göre)
                null,                           // requiredIp (henüz yok)
                "OFFERED",                      // state
                networkConfig.getDnsServers(),  // dns (VLAN'a göre)
                networkConfig.getGateway(),     // gateway (VLAN'a göre)
                networkConfig.getServerIdentifier(), // serverIdentifier (VLAN'a göre)
                networkConfig.getSubnetMask(),  // subnetMask (VLAN'a göre)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 saat)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );
    }

    /**
     * Request için yeni cihaz oluşturur
     */
    private DeviceInfo createDeviceForRequest(DhcpSimulationRequest request) {
        // Network konfigürasyonunu al
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());
        String offeredIP = deviceService.generateUniqueIPAddress(request.getCTag());

        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                offeredIP,                      // ipAddress (offer'da alınan IP - VLAN'a göre)
                offeredIP,                      // requiredIp (request edilecek IP)
                "REQUESTING",                   // state
                networkConfig.getDnsServers(),  // dns (VLAN'a göre)
                networkConfig.getGateway(),     // gateway (VLAN'a göre)
                networkConfig.getServerIdentifier(), // serverIdentifier (VLAN'a göre)
                networkConfig.getSubnetMask(),  // subnetMask (VLAN'a göre)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 saat)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );
    }

    /**
     * ACK için yeni cihaz oluşturur
     */
    private DeviceInfo createDeviceForAck(DhcpSimulationRequest request) {
        // Network konfigürasyonunu al
        DeviceService.NetworkConfiguration networkConfig = deviceService.getNetworkConfiguration(request.getCTag());
        String requestedIP = deviceService.generateUniqueIPAddress(request.getCTag());

        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                requestedIP,                    // ipAddress (onaylanacak IP - VLAN'a göre)
                requestedIP,                    // requiredIp (request edilmiş IP)
                "ACKNOWLEDGED",                 // state (ACK ile bound olacak)
                networkConfig.getDnsServers(),  // dns (VLAN'a göre)
                networkConfig.getGateway(),     // gateway (VLAN'a göre)
                networkConfig.getServerIdentifier(), // serverIdentifier (VLAN'a göre)
                networkConfig.getSubnetMask(),  // subnetMask (VLAN'a göre)
                0,                              // xid
                defaultLeaseTime,               // leaseTime (24 saat)
                request.getCTag(),              // vlanId
                request.getPonPort(),           // ponPort
                request.getGemPort(),           // gemPort
                request.getUniId(),             // uniId
                request.getOnuId(),             // onuId
                java.time.Instant.now()         // leaseStartTime
        );
    }

    /**
     * DHCP Discover paketi gönder
     */
    public void sendDhcpDiscover(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        // DHCP Discovery paketi oluştur
        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),            // VLAN tag
                clientMac,                     // Client MAC
                broadcastMac,                  // Destination MAC (broadcast)
                clientMac,                     // Source MAC
                DHCP_DISCOVER,                 // Message type
                "0.0.0.0",                     // Client IP (discovery'de 0.0.0.0)
                null,                          // Offered IP (discovery'de null)
                "0.0.0.0",                     // Server IP (henüz bilinmiyor)
                "0.0.0.0",                     // Gateway IP (henüz bilinmiyor)
                null,                          // Subnet mask (discovery'de null)
                null,                          // DNS servers (discovery'de null)
                0,                             // Lease time (discovery'de 0)
                device.getXid(),               // Transaction ID
                "0.0.0.0",                     // Source IP address
                "255.255.255.255"              // Destination IP (broadcast)
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * DHCP Offer paketi gönder
     */
    public void sendDhcpOffer(DeviceInfo device) {
        initializeMacAddresses();
        byte[] clientMac = macStringToBytes(device.getClientMac());

        String[] dnsServers = null;
        if (device.getDns() != null && !device.getDns().isEmpty()) {
            dnsServers = device.getDns().split(",");
            // Whitespace'leri temizle
            for (int i = 0; i < dnsServers.length; i++) {
                dnsServers[i] = dnsServers[i].trim();
            }
        }

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                clientMac,                      // Destination MAC (client'a gönderilir)
                serverMac,                      // Source MAC (server)
                DHCP_OFFER,                     // Message type
                device.getIpAddress(),          // Client IP (teklif edilen IP)
                null,                           // Offered IP (offer'da kullanılmaz)
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
     * DHCP Request paketi gönder
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
                "0.0.0.0",                      // Client IP (request'te genelde 0.0.0.0)
                device.getRequiredIp(),         // Offered IP (istenen IP)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                null,                           // DNS servers (request'te gönderilmez)
                0,                              // Lease time (request'te gönderilmez)
                device.getXid(),                // Transaction ID
                "0.0.0.0",                      // Source IP address
                "255.255.255.255"               // Destination IP (broadcast)
        );

        sendPacketIndication(device, dhcpPacket);
    }

    /**
     * DHCP ACK paketi gönder
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
                clientMac,                      // Destination MAC (client'a gönderilir)
                serverMac,                      // Source MAC (server)
                DHCP_ACK,                       // Message type
                device.getIpAddress(),          // Client IP (onaylanan IP)
                null,                           // Offered IP (ACK'da kullanılmaz)
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
     * MAC string'ini byte array'e çevirir
     */
    private byte[] macStringToBytes(String macString) {
        String[] parts = macString.split(":");
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            mac[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return mac;
    }

    private void sendPacketIndication(DeviceInfo device, byte[] dhcpPacket) {
        if (dhcpPacket == null || dhcpPacket.length == 0) {
            System.err.println("Invalid DHCP packet data");
            return;
        }
        try {
            PacketIndication.Builder pktBuilder = PacketIndication.newBuilder()
                    .setIntfType("pon")
                    .setIntfId(device.getPonPort())
                    .setGemportId(device.getGemPort())
                    .setPkt(ByteString.copyFrom(dhcpPacket));

            // Optional alanları kontrollü ekle
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
                        return false; // Stream çalışıyor
                    } catch (Exception e) {
                        System.err.println("Client stream error, removing: " + e.getMessage());
                        try {
                            stream.onError(e);
                        } catch (Exception ignored) {}
                        return true; // Stream'i kaldır
                    }
                });
            }

        } catch (Exception e) {
            System.err.println("Error creating packet indication: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void simulateDhcpStorm(Integer rate, Double intervalSec) {
        synchronized (stormLock) {
            if (stormInProgress) {
                throw new RuntimeException("DHCP storm is already in progress. Please wait for current storm to complete.");
            }
            stormInProgress = true;
        }

        DeviceWebSocket.broadcastStormStatus("progress", rate, intervalSec, "Storm started");

        // Delay hesaplama
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

        // Toplam cihaz sayısını hesapla
        int totalDevices = ponPortCount * onuPortCount;

        currentStormFuture = CompletableFuture.runAsync(() -> {
            try {
                System.out.println("DHCP Storm started - Rate: " +
                        (rate != null ? rate + " devices/sec" : "1 device per " + intervalSec + " seconds"));
                System.out.println("Total devices to create: " + totalDevices +
                        " (PON: " + ponPortStart + "-" + (ponPortStart + ponPortCount - 1) +
                        ", ONU: " + onuPortStart + "-" + (onuPortStart + onuPortCount - 1) + ")");

                Random random = new Random();
                int successCount = 0;
                int failureCount = 0;
                int deviceIndex = 0;

                // PON port'lar üzerinde döngü
                for (int ponIndex = 0; ponIndex < ponPortCount; ponIndex++) {
                    int currentPonPort = ponPortStart + ponIndex;

                    // ONU port'lar üzerinde döngü
                    for (int onuIndex = 0; onuIndex < onuPortCount; onuIndex++) {
                        int currentOnuPort = onuPortStart + onuIndex;
                        deviceIndex++;

                        try {
                            // Thread interruption kontrolü
                            if (Thread.currentThread().isInterrupted()) {
                                System.out.println("Storm interrupted at device " + deviceIndex);
                                return;
                            }

                            // Sabit ve rastgele parametreler
                            int uniId = 0; // UNI her zaman 0
                            int gemPort = 1024 + random.nextInt(2048); // GEM port rastgele
                            int cTag = 100 + random.nextInt(3994); // C-TAG rastgele

                            // DhcpSimulationRequest objesi oluştur
                            DhcpSimulationRequest stormRequest = new DhcpSimulationRequest(
                                    "discovery", currentPonPort, currentOnuPort, uniId, gemPort, cTag
                            );

                            // Discovery cihazı oluştur ve gönder
                            DeviceInfo stormDevice = createDeviceForDiscovery(stormRequest);
                            deviceService.addDevice(stormDevice);
                            sendDhcpDiscover(stormDevice);

                            successCount++;

                            // Progress log (her 50 cihazda bir veya PON port değişiminde)
                            if (deviceIndex % 50 == 0 || onuIndex == onuPortCount - 1) {
                                System.out.println("Storm progress: " + deviceIndex + "/" + totalDevices +
                                        " devices sent (PON: " + currentPonPort + ", ONU: " + currentOnuPort +
                                        ") - Success: " + successCount + ", Failed: " + failureCount);
                            }

                            // Son cihaz değilse delay uygula
                            if (deviceIndex < totalDevices) {
                                Thread.sleep(delayMs);
                            }

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.out.println("Storm interrupted at device " + deviceIndex);
                            return;
                        } catch (Exception e) {
                            failureCount++;
                            System.err.println("Error sending device " + deviceIndex +
                                    " (PON: " + currentPonPort + ", ONU: " + currentOnuPort + "): " + e.getMessage());
                            // Storm'a devam et, sadece bu cihazı atla
                        }
                    }

                    // Her PON port tamamlandığında log
                    System.out.println("PON port " + currentPonPort + " completed (" + onuPortCount + " ONUs)");
                }

                System.out.println("DHCP Storm completed: " + successCount + " devices sent successfully, " +
                        failureCount + " failed out of " + totalDevices + " total devices");

                DeviceWebSocket.broadcastStormStatus(
                        "ready",
                        null,
                        null,
                        "Storm completed successfully"
                );
            } catch (Exception e) {
                System.err.println("Fatal error during DHCP storm: " + e.getMessage());
                e.printStackTrace();
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
                System.out.println("DHCP Storm session ended");
            }
        }, managedExecutor);

        // Exception handling için future'ı handle et
        currentStormFuture.exceptionally(throwable -> {
            System.err.println("Storm execution error: " + throwable.getMessage());
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

    // Storm bilgileri için yeni metod
    public String getStormInfo() {
        int totalDevices = ponPortCount * onuPortCount;
        return "Storm Configuration - PON Ports: " + ponPortStart + "-" + (ponPortStart + ponPortCount - 1) +
                ", ONU Ports: " + onuPortStart + "-" + (onuPortStart + onuPortCount - 1) +
                ", Total Devices: " + totalDevices;
    }

    public boolean isStormInProgress() {
        synchronized (stormLock) {
            return stormInProgress;
        }
    }

    public void cancelStorm() {
        synchronized (stormLock) {
            if (stormInProgress && currentStormFuture != null) {
                System.out.println("Cancelling DHCP storm...");
                currentStormFuture.cancel(true);
                stormInProgress = false;
                currentStormFuture = null;
                System.out.println("DHCP storm cancelled");

                DeviceWebSocket.broadcastStormStatus(
                        "ready",
                        null,
                        null,
                        "Storm cancelled"
                );
            } else {
                System.out.println("No active storm to cancel");
            }
        }
    }

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
