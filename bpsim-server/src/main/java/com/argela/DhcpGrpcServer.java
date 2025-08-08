package com.argela;

import com.google.protobuf.ByteString;
import com.netsia.control.lib.api.packet.parsed.*;
import com.netsia.control.lib.api.packet.parsed.dhcp.DhcpOption;
import com.netsia.control.lib.api.packet.parsed.dhcp.DhcpRelayAgentOption;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;

import jakarta.inject.Inject;
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
    ManagedExecutor managedExecutor;

    private final Set<StreamObserver<Indication>> clientStreams = ConcurrentHashMap.newKeySet();

    // Global DHCP configuration values
    private static final String DEFAULT_DNS_SERVERS = "192.168.1.1";
    private static final String DEFAULT_GATEWAY = "192.168.1.1";
    private static final String DEFAULT_SERVER_IP = "192.168.1.1";
    private static final String DEFAULT_SUBNET_MASK = "255.255.255.0";
    private static final byte DEFAULT_VLAN_PRIORITY = (byte) 3;
    private static final long DEFAULT_LEASE_TIME = 86400;
    private static final String[] CACHED_DNS = DEFAULT_DNS_SERVERS.split(",");
    private static final byte[] SERVER_MAC = new byte[]{
            (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff
    };
    private static final byte[] BROADCAST_MAC = new byte[]{
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
    };

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
                    System.err.println("Error processing ONU packet: " + throwable.getMessage());
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

    private void processOnuPacket(VolthaOpenOLT.OnuPacket request) {
        try{
            Thread.sleep(1000);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
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

    private void processUplinkPacket(VolthaOpenOLT.UplinkPacket request) {
        try{
            Thread.sleep(1000);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
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
     * UDP paketinin DHCP paketi olup olmadığını kontrol eder
     */
    private boolean isDhcpPacket(UDP udpPacket) {
        return udpPacket.getSourcePort() == UDP.DHCP_SERVER_PORT ||
                udpPacket.getSourcePort() == UDP.DHCP_CLIENT_PORT ||
                udpPacket.getDestinationPort() == UDP.DHCP_SERVER_PORT ||
                udpPacket.getDestinationPort() == UDP.DHCP_CLIENT_PORT;
    }

    /**
     * DHCP paketinden mesaj türünü çıkarır
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
     * Gelen DHCP Discovery'yi işler ve Offer gönderir
     */
    private void handleReceivedDiscovery(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.UplinkPacket request) {
        // Device'ı OFFERING durumuna getir ve IP ata
        String assignedIP = generateUniqueIPAddress();
        device.setIpAddress(assignedIP);
        device.setState("OFFERING");
        device.setDns(DEFAULT_DNS_SERVERS);
        device.setGateway(DEFAULT_GATEWAY);
        device.setServerIdentifier(DEFAULT_SERVER_IP);
        device.setSubnetMask(DEFAULT_SUBNET_MASK);
        device.setLeaseTime(DEFAULT_LEASE_TIME);

        deviceService.updateDevice(device);

        // DHCP Offer gönder
        sendDhcpOffer(device);
    }

    /**
     * Gelen DHCP Request'i işler ve ACK gönderir
     */
    private void handleReceivedRequest(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.UplinkPacket request) {
        // Request'teki istenen IP'yi al (Option 50)
        String requestedIP = getRequestedIP(dhcpPacket);
        if (requestedIP != null) {
            device.setRequiredIp(requestedIP);
            device.setIpAddress(requestedIP); // ACK'da aynı IP'yi onayla
        }

        // Device'ı ACKNOWLEDGING durumuna getir
        device.setState("ACKNOWLEDGING");
        device.setLeaseStartTime(Instant.now());

        deviceService.updateDevice(device);

        // DHCP ACK gönder
        sendDhcpAck(device);
    }

    /**
     * Gelen DHCP Offer'ı işler ve Request gönderir
     */
    private void handleReceivedOffer(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.OnuPacket request) {
        // Offer'dan teklif edilen IP'yi al
        int offeredIP = dhcpPacket.getYourIPAddress();
        String offeredIPStr = IPv4.fromIPv4Address(offeredIP);

        device.setIpAddress(offeredIPStr);
        device.setRequiredIp(offeredIPStr);
        device.setState("REQUESTING");

        deviceService.updateDevice(device);

        // DHCP Request gönder
        sendDhcpRequest(device);
    }

    /**
     * Gelen DHCP ACK'ı işler ve işlemi tamamlar
     */
    private void handleReceivedAck(DeviceInfo device, DHCP dhcpPacket, VolthaOpenOLT.OnuPacket request) {
        // ACK'dan onaylanan IP'yi al
        int confirmedIP = dhcpPacket.getYourIPAddress();
        String confirmedIPStr = IPv4.fromIPv4Address(confirmedIP);

        device.setIpAddress(confirmedIPStr);
        device.setState("ACKNOWLEDGED"); // IP adresi başarıyla atandı
        device.setLeaseStartTime(Instant.now());
        if (device.getDhcpStartTime() != null) {
            long dhcpDuration = device.getDhcpCompletionTimeMs();
        }

        deviceService.updateDevice(device);
    }

    /**
     * DHCP Request paketinden istenen IP adresini çıkarır (Option 50)
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
     * Benzersiz IP adresi üretir
     */
    private String generateUniqueIPAddress() {
        return deviceService.generateUniqueIPAddress(); // DeviceService'e yönlendir
    }


    /**
     * DHCP Server paketleri oluşturur (Offer ve ACK)
     * @param cTag VLAN tag değeri
     * @param clientMac Client MAC adresi
     * @param destinationMac Destination MAC adresi
     * @param sourceMac Source MAC adresi
     * @param messageType DHCP mesaj türü (DHCP_OFFER veya DHCP_ACK)
     * @param clientIP Client'a verilecek IP adresi
     * @param offeredIP Request için teklif edilen IP (Discover için null olabilir)
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
        eth.setPriorityCode(DEFAULT_VLAN_PRIORITY);
        eth.setPayload(ipv4);

        return eth.serialize();
    }

    public void sendDhcp(DhcpSimulationRequest request){
        String packetType = request.getPacketType().toLowerCase();
        switch(packetType) {
            case "discovery":
                // Discovery için yeni cihaz oluştur
                DeviceInfo discoveryDevice = createDeviceForDiscovery(request);
                deviceService.addDevice(discoveryDevice);
                sendDhcpDiscover(discoveryDevice);
                break;
            case "offer":
                // Offer için yeni cihaz oluştur
                DeviceInfo offerDevice = createDeviceForOffer(request);
                deviceService.addDevice(offerDevice);
                sendDhcpOffer(offerDevice);
                break;
            case "request":
                // Request için yeni cihaz oluştur
                DeviceInfo requestDevice = createDeviceForRequest(request);
                deviceService.addDevice(requestDevice);
                sendDhcpRequest(requestDevice);
                break;
            case "ack":
                // ACK için yeni cihaz oluştur
                DeviceInfo ackDevice = createDeviceForAck(request);
                deviceService.addDevice(ackDevice);
                sendDhcpAck(ackDevice);
                break;
            default:
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
                null,                           // dns (henüz yok)
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
     * Offer için yeni cihaz oluşturur (sanki discovery yapmış gibi)
     */
    private DeviceInfo createDeviceForOffer(DhcpSimulationRequest request) {
        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                generateUniqueIPAddress(),      // ipAddress (offer edilecek IP)
                null,                           // requiredIp (henüz yok)
                "OFFERED",                      // state
                DEFAULT_DNS_SERVERS,            // dns
                DEFAULT_GATEWAY,                // gateway
                DEFAULT_SERVER_IP,              // serverIdentifier
                DEFAULT_SUBNET_MASK,            // subnetMask
                0,                              // xid
                DEFAULT_LEASE_TIME,             // leaseTime (24 saat)
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
        String offeredIP = generateUniqueIPAddress();

        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                offeredIP,                      // ipAddress (offer'da alınan IP)
                offeredIP,                      // requiredIp (request edilecek IP)
                "REQUESTING",                   // state
                DEFAULT_DNS_SERVERS,            // dns
                DEFAULT_GATEWAY,                // gateway
                DEFAULT_SERVER_IP,              // serverIdentifier
                DEFAULT_SUBNET_MASK,            // subnetMask
                0,                              // xid
                DEFAULT_LEASE_TIME,             // leaseTime (24 saat)
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
        String requestedIP = generateUniqueIPAddress();

        return new DeviceInfo(
                0,                           // id (DeviceService'de otomatik atanacak)
                null,                           // clientMac
                requestedIP,                    // ipAddress (onaylanacak IP)
                requestedIP,                    // requiredIp (request edilmiş IP)
                "ACKNOWLEDGED",                 // state (ACK ile bound olacak)
                DEFAULT_DNS_SERVERS,            // dns
                DEFAULT_GATEWAY,                // gateway
                DEFAULT_SERVER_IP,              // serverIdentifier
                DEFAULT_SUBNET_MASK,            // subnetMask
                0,                              // xid
                DEFAULT_LEASE_TIME,             // leaseTime (24 saat)
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
        // Device'dan MAC adresini al
        byte[] clientMac = macStringToBytes(device.getClientMac());

        // DHCP Discovery paketi oluştur
        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),            // VLAN tag
                clientMac,                     // Client MAC
                BROADCAST_MAC,                 // Destination MAC (broadcast)
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
        byte[] clientMac = macStringToBytes(device.getClientMac());

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                clientMac,                      // Destination MAC (client'a gönderilir)
                SERVER_MAC,                     // Source MAC (server)
                DHCP_OFFER,                     // Message type
                device.getIpAddress(),          // Client IP (teklif edilen IP)
                null,                           // Offered IP (offer'da kullanılmaz)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                CACHED_DNS,                     // DNS servers
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
        byte[] clientMac = macStringToBytes(device.getClientMac());

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                BROADCAST_MAC,                  // Destination MAC (broadcast)
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
        byte[] clientMac = macStringToBytes(device.getClientMac());

        byte[] dhcpPacket = createDhcpPacket(
                device.getVlanId(),             // VLAN tag
                clientMac,                      // Client MAC
                clientMac,                      // Destination MAC (client'a gönderilir)
                SERVER_MAC,                     // Source MAC (server)
                DHCP_ACK,                       // Message type
                device.getIpAddress(),          // Client IP (onaylanan IP)
                null,                           // Offered IP (ACK'da kullanılmaz)
                device.getServerIdentifier(),   // Server IP
                device.getGateway(),            // Gateway IP
                device.getSubnetMask(),         // Subnet mask
                CACHED_DNS,                     // DNS servers
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

    /**
     * Packet indication gönderme helper method
     */
    private void sendPacketIndication(DeviceInfo device, byte[] dhcpPacket) {
        Indication indication = Indication.newBuilder()
                .setPktInd(
                        PacketIndication.newBuilder()
                                .setIntfType("pon")
                                .setIntfId(device.getPonPort())
                                .setOnuId(device.getOnuId())
                                .setUniId(device.getUniId())
                                .setGemportId(device.getGemPort())
                                .setPkt(ByteString.copyFrom(dhcpPacket))
                                .build()
                )
                .build();

        clientStreams.removeIf(stream -> {
            try {
                stream.onNext(indication);
                return false; // Stream çalışıyor, sakla
            } catch (Exception e) {
                System.out.println("Disconnected client removed. Remaining: " + clientStreams.size());
                return true; // Stream bozuk, kaldır
            }
        });
    }
}
