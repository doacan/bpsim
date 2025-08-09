package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class DeviceService {
    @ConfigProperty(name = "dhcp.device.max.count", defaultValue = "10000")
    int maxDevices;

    private final ConcurrentHashMap<Integer, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final AtomicInteger deviceIdCounter = new AtomicInteger(0);
    private final AtomicInteger xidCounter = new AtomicInteger(new Random().nextInt(1000000));
    private final ThreadLocal<Random> localRandom = ThreadLocal.withInitial(Random::new);
    private final Set<String> macAddresses = ConcurrentHashMap.newKeySet();
    private final Set<Integer> xids = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();
    private final ConcurrentHashMap<Integer, DeviceInfo> devicesByXid = new ConcurrentHashMap<>();

    @Inject
    VlanIPPoolManager vlanIPPoolManager;

    public void addDevice(DeviceInfo device) {
        // Limit kontrolü
        if (devices.size() >= maxDevices) {
            throw new RuntimeException("Maximum device limit reached: " + maxDevices);
        }

        // MAC adresi yoksa üret
        if (device.getClientMac() == null || device.getClientMac().isEmpty()) {
            String newMac = generateUniqueMac();
            device.setClientMac(newMac);
        }

        // MAC'in unique olduğundan emin ol
        if (!macAddresses.add(device.getClientMac())) {
            throw new RuntimeException("MAC address already exists: " + device.getClientMac());
        }

        // XID yoksa üret
        if (device.getXid() == 0) {
            int newXid = generateUniqueXID();
            device.setXid(newXid);
        }

        // XID'nin unique olduğundan emin ol
        if (!xids.add(device.getXid())) {
            macAddresses.remove(device.getClientMac()); // Rollback
            throw new RuntimeException("XID already exists: " + device.getXid());
        }

        // ID ata
        int newId = deviceIdCounter.getAndIncrement();
        device.setId(newId);

        devices.put(newId, device);
        devicesByXid.put(device.getXid(), device);

        DeviceWebSocket.broadcastDevice(device);
    }

    public void updateDevice(DeviceInfo device) {
        DeviceInfo existingDevice = devices.get(device.getId());
        if (existingDevice != null) {
            // Eski IP'yi serbest bırak (VLAN ile birlikte)
            if (existingDevice.getIpAddress() != null &&
                    !existingDevice.getIpAddress().equals(device.getIpAddress())) {
                vlanIPPoolManager.releaseIP(existingDevice.getIpAddress(), existingDevice.getVlanId());
            }

            devices.put(device.getId(), device);
            devicesByXid.put(device.getXid(), device);

            DeviceWebSocket.broadcastDevice(device);
        }
    }

    public void removeDevice(int id) {
        DeviceInfo device = devices.remove(id);
        if (device != null) {
            macAddresses.remove(device.getClientMac());
            xids.remove(device.getXid());
            devicesByXid.remove(device.getXid());

            // IP'yi VLAN pool'una geri ver
            if (device.getIpAddress() != null) {
                vlanIPPoolManager.releaseIP(device.getIpAddress(), device.getVlanId());
            }

            System.out.println("Device removed: ID=" + id + ", VLAN=" + device.getVlanId());
        }
    }

    public String generateUniqueIPAddress(int vlanId) {
        return vlanIPPoolManager.allocateIP(vlanId);
    }

    public NetworkConfiguration getNetworkConfiguration(int vlanId) {
        return new NetworkConfiguration(
                vlanIPPoolManager.getGatewayIP(vlanId),
                vlanIPPoolManager.getDNSServerIP(vlanId) + "," + vlanIPPoolManager.getSecondaryDNSServerIP(vlanId),
                vlanIPPoolManager.getServerIdentifierIP(vlanId),
                vlanIPPoolManager.getSubnetMask(),
                vlanIPPoolManager.getNetworkIP(vlanId),
                vlanIPPoolManager.getBroadcastIP(vlanId)
        );
    }

    public Optional<DeviceInfo> findDeviceByXid(int xid) {
        return Optional.ofNullable(devicesByXid.get(xid));
    }

    public Optional<DeviceInfo> findDeviceById(int id) {
        return Optional.ofNullable(devices.get(id));
    }

    public Optional<DeviceInfo> findDeviceByMac(String mac) {
        return devices.values().stream()
                .filter(device -> mac.equals(device.getClientMac()))
                .findFirst();
    }

    public boolean isIPAddressInUse(String ipAddress, int vlanId) {
        return vlanIPPoolManager.isIPInUse(ipAddress, vlanId);
    }

    public Collection<DeviceInfo> getAllDevices() {
        return devices.values();
    }

    public DeviceInfo getDevice(int id) {
        return devices.get(id);
    }

    public boolean isMacAddressInUse(String macAddress) {
        return macAddresses.contains(macAddress);
    }

    public List<DeviceInfo> getDevicesByState(String state) {
        return devices.values().stream()
                .filter(device -> state.equals(device.getState()))
                .toList();
    }

    public List<DeviceInfo> getDevicesByVlan(int vlanId) {
        return devices.values().stream()
                .filter(device -> device.getVlanId() == vlanId)
                .toList();
    }

    public void cleanExpiredLeases() {
        Instant now = Instant.now();
        List<DeviceInfo> expiredDevices = devices.values().stream()
                .filter(device -> device.getLeaseStartTime() != null &&
                        device.getLeaseTime() > 0 &&
                        now.isAfter(device.getLeaseStartTime().plusSeconds(device.getLeaseTime())))
                .toList();

        for (DeviceInfo device : expiredDevices) {
            System.out.println("Removing expired lease for device ID: " + device.getId() + ", VLAN: " + device.getVlanId());
            removeDevice(device.getId());
        }
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // Device durumlarına göre sayım
        Map<String, Long> stateCount = devices.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        DeviceInfo::getState,
                        java.util.stream.Collectors.counting()));

        // VLAN bazında device sayım
        Map<Integer, Long> vlanDeviceCount = devices.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        DeviceInfo::getVlanId,
                        java.util.stream.Collectors.counting()));

        stats.put("totalDevices", devices.size());
        stats.put("stateCount", stateCount);
        stats.put("vlanDeviceCount", vlanDeviceCount);
        stats.put("usedMacAddresses", macAddresses.size());
        stats.put("nextDeviceId", deviceIdCounter.get());
        stats.put("vlanPoolStatistics", vlanIPPoolManager.getAllStatistics());

        return stats;
    }

    public void clearAll() {
        // Tüm IP'leri serbest bırak
        devices.values().forEach(device -> {
            if (device.getIpAddress() != null) {
                vlanIPPoolManager.releaseIP(device.getIpAddress(), device.getVlanId());
            }
        });

        devices.clear();
        devicesByXid.clear();
        macAddresses.clear();
        xids.clear();
        deviceIdCounter.set(0);
        vlanIPPoolManager.clearAll();
    }

    private int generateUniqueXID() {
        int newXid;
        do {
            newXid = xidCounter.incrementAndGet();
            if (newXid <= 0) {
                // Overflow durumunda reset
                xidCounter.compareAndSet(newXid, 1);
                newXid = 1;
            }
        } while (xids.contains(newXid));
        return newXid;
    }

    private String generateUniqueMac() {
        String newMac;
        Random random = localRandom.get();

        do {
            byte[] mac = new byte[6];
            random.nextBytes(mac);
            // MAC adresinin ilk byte'ının LSB biti 0 olmalı (unicast)
            mac[0] = (byte)(mac[0] & (byte)254);
            // İlk byte'ın ikinci LSB biti 0 olmalı (globally unique)
            mac[0] = (byte)(mac[0] & (byte)253);
            newMac = String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                    mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        } while (macAddresses.contains(newMac));

        return newMac;
    }

    public static class NetworkConfiguration {
        private final String gateway;
        private final String dnsServers;
        private final String serverIdentifier;
        private final String subnetMask;
        private final String networkIP;
        private final String broadcastIP;

        public NetworkConfiguration(String gateway, String dnsServers, String serverIdentifier,
                                    String subnetMask, String networkIP, String broadcastIP) {
            this.gateway = gateway;
            this.dnsServers = dnsServers;
            this.serverIdentifier = serverIdentifier;
            this.subnetMask = subnetMask;
            this.networkIP = networkIP;
            this.broadcastIP = broadcastIP;
        }

        // Getters
        public String getGateway() { return gateway; }
        public String getDnsServers() { return dnsServers; }
        public String getServerIdentifier() { return serverIdentifier; }
        public String getSubnetMask() { return subnetMask; }
        public String getNetworkIP() { return networkIP; }
        public String getBroadcastIP() { return broadcastIP; }
    }
}
