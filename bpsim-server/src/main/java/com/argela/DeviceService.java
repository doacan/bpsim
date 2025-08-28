package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class DeviceService {
    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);

    @ConfigProperty(name = "dhcp.device.max.count", defaultValue = "10000")
    int maxDevices;

    private final ConcurrentHashMap<Integer, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final AtomicInteger deviceIdCounter = new AtomicInteger(0);
    private final AtomicInteger xidCounter = new AtomicInteger(new Random().nextInt(1000000));
    private final ThreadLocal<Random> localRandom = ThreadLocal.withInitial(Random::new);
    private final Set<String> macAddresses = ConcurrentHashMap.newKeySet();
    private final Set<Integer> xids = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, DeviceInfo> devicesByXid = new ConcurrentHashMap<>();

    @Inject
    VlanIPPoolManager vlanIPPoolManager;

    /**
     * Adds a new device to the system
     * @param device The device information to add
     * @throws RuntimeException if maximum device limit is reached or MAC/XID already exists
     */
    public void addDevice(DeviceInfo device) {
        logger.debug("Adding device to system: vlanId={}, ponPort={}, onuId={}, state={}",
                device.getVlanId(), device.getPonPort(), device.getOnuId(), device.getState());

        // Check device limit
        if (devices.size() >= maxDevices) {
            logger.error("Maximum device limit reached: {}", maxDevices);
            throw new RuntimeException("Maximum device limit reached: " + maxDevices);
        }

        // Generate MAC address if not present
        if (device.getClientMac() == null || device.getClientMac().isEmpty()) {
            String newMac = generateUniqueMac();
            device.setClientMac(newMac);
            logger.debug("Generated unique MAC address: {}", newMac);
        }

        // Ensure MAC is unique
        if (!macAddresses.add(device.getClientMac())) {
            logger.error("MAC address already exists: {}", device.getClientMac());
            throw new RuntimeException("MAC address already exists: " + device.getClientMac());
        }

        // Generate XID if not present
        if (device.getXid() == 0) {
            int newXid = generateUniqueXID();
            device.setXid(newXid);
            logger.debug("Generated unique XID: {}", newXid);
        }

        // Ensure XID is unique
        if (!xids.add(device.getXid())) {
            macAddresses.remove(device.getClientMac()); // Rollback
            logger.error("XID already exists: {}", device.getXid());
            throw new RuntimeException("XID already exists: " + device.getXid());
        }

        // Assign ID
        int newId = deviceIdCounter.getAndIncrement();
        device.setId(newId);

        devices.put(newId, device);
        devicesByXid.put(device.getXid(), device);

        logger.info("Device added successfully: ID={}, MAC={}, XID={}, VLAN={}, IP={}",
                newId, device.getClientMac(), device.getXid(), device.getVlanId(), device.getIpAddress());

        DeviceWebSocket.broadcastDevice(device);
    }

    /**
     * Updates an existing device
     * @param device The device information to update
     */
    public void updateDevice(DeviceInfo device) {
        logger.debug("Updating device: ID={}, state={}, IP={}",
                device.getId(), device.getState(), device.getIpAddress());

        DeviceInfo existingDevice = devices.get(device.getId());
        if (existingDevice != null) {
            // Release old IP (with VLAN)
            if (existingDevice.getIpAddress() != null &&
                    !existingDevice.getIpAddress().equals(device.getIpAddress())) {
                logger.debug("Releasing old IP address: {} for VLAN: {}",
                        existingDevice.getIpAddress(), existingDevice.getVlanId());
                vlanIPPoolManager.releaseIP(existingDevice.getIpAddress(), existingDevice.getVlanId());
            }

            devices.put(device.getId(), device);
            devicesByXid.put(device.getXid(), device);

            logger.info("Device updated successfully: ID={}, state={}, IP={}, duration={}ms",
                    device.getId(), device.getState(), device.getIpAddress(), device.getDhcpDurationMs());

            DeviceWebSocket.broadcastDevice(device);
        } else {
            logger.warn("Attempted to update non-existent device: ID={}", device.getId());
        }
    }

    /**
     * Removes a device from the system
     * @param id The device ID to remove
     */
    public void removeDevice(int id) {
        logger.debug("Removing device: ID={}", id);

        DeviceInfo device = devices.remove(id);
        if (device != null) {
            macAddresses.remove(device.getClientMac());
            xids.remove(device.getXid());
            devicesByXid.remove(device.getXid());

            // Return IP to VLAN pool
            if (device.getIpAddress() != null) {
                logger.debug("Releasing IP address: {} for VLAN: {}",
                        device.getIpAddress(), device.getVlanId());
                vlanIPPoolManager.releaseIP(device.getIpAddress(), device.getVlanId());
            }

            logger.info("Device removed successfully: ID={}, MAC={}, VLAN={}, IP={}",
                    id, device.getClientMac(), device.getVlanId(), device.getIpAddress());
        } else {
            logger.warn("Attempted to remove non-existent device: ID={}", id);
        }
    }

    /**
     * Generates a unique IP address for the specified VLAN
     * @param vlanId The VLAN ID to generate IP for
     * @return Unique IP address string
     */
    public String generateUniqueIPAddress(int vlanId) {
        logger.debug("Generating unique IP address for VLAN: {}", vlanId);
        String ip = vlanIPPoolManager.allocateIP(vlanId);
        logger.debug("Allocated IP address: {} for VLAN: {}", ip, vlanId);
        return ip;
    }

    /**
     * Gets network configuration for the specified VLAN
     * @param vlanId The VLAN ID to get configuration for
     * @return Network configuration object containing gateway, DNS, subnet mask etc.
     */
    public NetworkConfiguration getNetworkConfiguration(int vlanId) {
        logger.debug("Getting network configuration for VLAN: {}", vlanId);
        return new NetworkConfiguration(
                vlanIPPoolManager.getGatewayIP(vlanId),
                vlanIPPoolManager.getDNSServerIP(vlanId) + "," + vlanIPPoolManager.getSecondaryDNSServerIP(vlanId),
                vlanIPPoolManager.getServerIdentifierIP(vlanId),
                vlanIPPoolManager.getSubnetMask(),
                vlanIPPoolManager.getNetworkIP(vlanId),
                vlanIPPoolManager.getBroadcastIP(vlanId)
        );
    }

    /**
     * Finds a device by transaction ID
     * @param xid The transaction ID to search for
     * @return Optional containing the device if found
     */
    public Optional<DeviceInfo> findDeviceByXid(int xid) {
        logger.debug("Finding device by XID: {}", xid);
        Optional<DeviceInfo> device = Optional.ofNullable(devicesByXid.get(xid));
        if (device.isPresent()) {
            logger.debug("Found device by XID: {} -> ID={}", xid, device.get().getId());
        } else {
            logger.debug("No device found for XID: {}", xid);
        }
        return device;
    }

    /**
     * Finds a device by device ID
     * @param id The device ID to search for
     * @return Optional containing the device if found
     */
    public Optional<DeviceInfo> findDeviceById(int id) {
        logger.debug("Finding device by ID: {}", id);
        Optional<DeviceInfo> device = Optional.ofNullable(devices.get(id));
        if (device.isPresent()) {
            logger.debug("Found device by ID: {} -> MAC={}, state={}", id, device.get().getClientMac(), device.get().getState());
        } else {
            logger.debug("No device found for ID: {}", id);
        }
        return device;
    }

    /**
     * Finds a device by MAC address
     * @param mac The MAC address to search for
     * @return Optional containing the device if found
     */
    public Optional<DeviceInfo> findDeviceByMac(String mac) {
        logger.debug("Finding device by MAC: {}", mac);
        Optional<DeviceInfo> device = devices.values().stream()
                .filter(d -> mac.equals(d.getClientMac()))
                .findFirst();
        if (device.isPresent()) {
            logger.debug("Found device by MAC: {} -> ID={}", mac, device.get().getId());
        } else {
            logger.debug("No device found for MAC: {}", mac);
        }
        return device;
    }

    /**
     * Checks if an IP address is currently in use for the specified VLAN
     * @param ipAddress The IP address to check
     * @param vlanId The VLAN ID to check in
     * @return true if IP is in use, false otherwise
     */
    public boolean isIPAddressInUse(String ipAddress, int vlanId) {
        logger.debug("Checking IP address usage: {} in VLAN: {}", ipAddress, vlanId);
        return vlanIPPoolManager.isIPInUse(ipAddress, vlanId);
    }

    /**
     * Gets all devices in the system
     * @return Collection of all device information
     */
    public Collection<DeviceInfo> getAllDevices() {
        logger.debug("Getting all devices, total count: {}", devices.size());
        return devices.values();
    }

    /**
     * Gets a specific device by ID
     * @param id The device ID to retrieve
     * @return Device information or null if not found
     */
    public DeviceInfo getDevice(int id) {
        logger.debug("Getting device by ID: {}", id);
        return devices.get(id);
    }

    /**
     * Checks if a MAC address is currently in use
     * @param macAddress The MAC address to check
     * @return true if MAC is in use, false otherwise
     */
    public boolean isMacAddressInUse(String macAddress) {
        logger.debug("Checking MAC address usage: {}", macAddress);
        return macAddresses.contains(macAddress);
    }

    /**
     * Gets all devices with the specified state
     * @param state The device state to filter by
     * @return List of devices with the specified state
     */
    public List<DeviceInfo> getDevicesByState(String state) {
        logger.debug("Getting devices by state: {}", state);
        List<DeviceInfo> deviceList = devices.values().stream()
                .filter(device -> state.equals(device.getState()))
                .toList();
        logger.debug("Found {} devices in state: {}", deviceList.size(), state);
        return deviceList;
    }

    /**
     * Gets all devices in the specified VLAN
     * @param vlanId The VLAN ID to filter by
     * @return List of devices in the specified VLAN
     */
    public List<DeviceInfo> getDevicesByVlan(int vlanId) {
        logger.debug("Getting devices by VLAN: {}", vlanId);
        List<DeviceInfo> deviceList = devices.values().stream()
                .filter(device -> device.getVlanId() == vlanId)
                .toList();
        logger.debug("Found {} devices in VLAN: {}", deviceList.size(), vlanId);
        return deviceList;
    }

    /**
     * Removes devices with expired DHCP leases
     */
    public void cleanExpiredLeases() {
        logger.debug("Cleaning expired leases");
        Instant now = Instant.now();
        List<DeviceInfo> expiredDevices = devices.values().stream()
                .filter(device -> device.getLeaseStartTime() != null &&
                        device.getLeaseTime() > 0 &&
                        now.isAfter(device.getLeaseStartTime().plusSeconds(device.getLeaseTime())))
                .toList();

        logger.info("Found {} expired devices to clean", expiredDevices.size());

        for (DeviceInfo device : expiredDevices) {
            logger.info("Removing expired lease for device ID: {}, VLAN: {}, IP: {}",
                    device.getId(), device.getVlanId(), device.getIpAddress());
            removeDevice(device.getId());
        }
    }

    /**
     * Gets system statistics including device counts and VLAN information
     * @return Map containing various system statistics
     */
    public Map<String, Object> getStatistics() {
        logger.debug("Generating system statistics");
        Map<String, Object> stats = new HashMap<>();

        // Count devices by state
        Map<String, Long> stateCount = devices.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        DeviceInfo::getState,
                        java.util.stream.Collectors.counting()));

        // Count devices by VLAN
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

        logger.debug("Generated statistics for {} devices across {} VLANs",
                devices.size(), vlanDeviceCount.size());

        return stats;
    }

    /**
     * Clears all devices and resets the system
     */
    public void clearAll() {
        logger.info("Clearing all devices and resetting system");
        int deviceCount = devices.size();

        // Release all IPs
        devices.values().forEach(device -> {
            if (device.getIpAddress() != null) {
                logger.debug("Releasing IP: {} for VLAN: {}", device.getIpAddress(), device.getVlanId());
                vlanIPPoolManager.releaseIP(device.getIpAddress(), device.getVlanId());
            }
        });

        devices.clear();
        devicesByXid.clear();
        macAddresses.clear();
        xids.clear();
        deviceIdCounter.set(0);
        vlanIPPoolManager.clearAll();

        DeviceWebSocket.broadcastClearAll();

        logger.info("System cleared successfully. {} devices removed", deviceCount);
    }

    /**
     * Generates a unique transaction ID
     * @return A unique XID value
     */
    private int generateUniqueXID() {
        int newXid;
        do {
            newXid = xidCounter.incrementAndGet();
            if (newXid <= 0) {
                // Reset on overflow
                xidCounter.compareAndSet(newXid, 1);
                newXid = 1;
            }
        } while (xids.contains(newXid));
        logger.debug("Generated unique XID: {}", newXid);
        return newXid;
    }

    /**
     * Generates a unique MAC address
     * @return A unique MAC address string in format xx:xx:xx:xx:xx:xx
     */
    private String generateUniqueMac() {
        String newMac;
        Random random = localRandom.get();

        do {
            byte[] mac = new byte[6];
            random.nextBytes(mac);
            // First byte LSB should be 0 (unicast)
            mac[0] = (byte)(mac[0] & (byte)254);
            // First byte second LSB should be 0 (globally unique)
            mac[0] = (byte)(mac[0] & (byte)253);
            newMac = String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                    mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        } while (macAddresses.contains(newMac));

        logger.debug("Generated unique MAC address: {}", newMac);
        return newMac;
    }

    /**
     * Network configuration container class
     */
    public static class NetworkConfiguration {
        private final String gateway;
        private final String dnsServers;
        private final String serverIdentifier;
        private final String subnetMask;
        private final String networkIP;
        private final String broadcastIP;

        /**
         * Creates a new network configuration
         * @param gateway Gateway IP address
         * @param dnsServers DNS servers (comma-separated)
         * @param serverIdentifier DHCP server identifier IP
         * @param subnetMask Subnet mask
         * @param networkIP Network IP address
         * @param broadcastIP Broadcast IP address
         */
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