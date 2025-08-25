package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VlanIPPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(VlanIPPoolManager.class);

    @ConfigProperty(name = "dhcp.network.base.ip", defaultValue = "10.0.0.0")
    String baseNetworkIP;

    @ConfigProperty(name = "dhcp.subnet.mask.bits", defaultValue = "24")
    int subnetMaskBits;

    @ConfigProperty(name = "dhcp.gateway.offset", defaultValue = "1")
    int gatewayOffset;

    @ConfigProperty(name = "dhcp.dns.primary.offset", defaultValue = "2")
    int primaryDnsOffset;

    @ConfigProperty(name = "dhcp.dns.secondary.offset", defaultValue = "3")
    int secondaryDnsOffset;

    @ConfigProperty(name = "dhcp.reserved.ips.start", defaultValue = "4")
    int reservedIpsStart;

    // VLAN range: 1-4094
    private static final int MIN_VLAN = 1;
    private static final int MAX_VLAN = 4094;

    // IP pool for each VLAN (254 IPs per VLAN, 2-255)
    private final ConcurrentHashMap<Integer, VlanSubnet> vlanSubnets = new ConcurrentHashMap<>();
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock();

    private int[] baseOctets;
    private int subnetMask;
    private int hostBits;
    private int maxHostsPerSubnet;
    private String subnetMaskString;

    /**
     * Initializes IP pools and calculates network parameters
     */
    @PostConstruct
    public void initializePools() {
        parseBaseNetwork();
        calculateSubnetParameters();
        int maxSupportedVlans = calculateMaxSupportedVlans();

        if (maxSupportedVlans < MAX_VLAN) {
            logger.warn("WARNING: Current configuration supports only {} VLANs, but system allows up to {}",
                    maxSupportedVlans, MAX_VLAN);
            logger.warn("Consider using a smaller subnet mask or different base network");
        }
    }

    /**
     * Parses the base network IP address into octets
     */
    private void parseBaseNetwork() {
        String[] parts = baseNetworkIP.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid base network IP format: " + baseNetworkIP);
        }
        baseOctets = new int[4];
        for (int i = 0; i < 4; i++) {
            baseOctets[i] = Integer.parseInt(parts[i]);
        }
    }

    /**
     * Calculates subnet parameters based on subnet mask bits
     */
    private void calculateSubnetParameters() {
        if (subnetMaskBits < 8 || subnetMaskBits > 30) {
            throw new IllegalArgumentException("Subnet mask bits must be between 8 and 30");
        }

        // Calculate subnet mask
        subnetMask = (0xFFFFFFFF << (32 - subnetMaskBits)) & 0xFFFFFFFF;
        hostBits = 32 - subnetMaskBits;
        maxHostsPerSubnet = (1 << hostBits) - 2; // Excluding network and broadcast

        // Generate subnet mask string
        int mask = subnetMask;
        subnetMaskString = String.format("%d.%d.%d.%d",
                (mask >>> 24) & 0xFF,
                (mask >>> 16) & 0xFF,
                (mask >>> 8) & 0xFF,
                mask & 0xFF);
    }

    /**
     * Calculates subnet information for given VLAN ID
     * @param vlanId The VLAN ID to calculate subnet for
     * @return SubnetInfo object containing subnet details
     */
    private SubnetInfo calculateSubnetForVlan(int vlanId) {
        validateVlanId(vlanId);
        validateVlanCapacity(vlanId);

        // Convert base network to 32-bit integer
        long baseNetworkLong = ((long)baseOctets[0] << 24) |
                ((long)baseOctets[1] << 16) |
                ((long)baseOctets[2] << 8) |
                baseOctets[3];

        // Calculate subnet offset based on VLAN ID
        long subnetSize = 1L << hostBits;
        long subnetOffset = (vlanId - 1) * subnetSize;
        long networkAddress = baseNetworkLong + subnetOffset;

        // Overflow check - should not exceed IPv4 range
        if (networkAddress > 0xFFFFFFFFL) {
            throw new RuntimeException(String.format(
                    "VLAN %d subnet address exceeds IPv4 range. " +
                            "Base network: %s, Subnet bits: %d, Max supported VLANs: %d",
                    vlanId, baseNetworkIP, subnetMaskBits, calculateMaxSupportedVlans()
            ));
        }

        // Calculate network and broadcast addresses
        long broadcastAddress = networkAddress + subnetSize - 1;

        // Broadcast should also not exceed IPv4 range
        if (broadcastAddress > 0xFFFFFFFFL) {
            throw new RuntimeException(String.format(
                    "VLAN %d broadcast address exceeds IPv4 range. " +
                            "Base network: %s, Subnet bits: %d, Max supported VLANs: %d",
                    vlanId, baseNetworkIP, subnetMaskBits, calculateMaxSupportedVlans()
            ));
        }

        return new SubnetInfo(
                longToIP(networkAddress),
                longToIP(broadcastAddress),
                longToIP(networkAddress + gatewayOffset),
                longToIP(networkAddress + primaryDnsOffset),
                longToIP(networkAddress + secondaryDnsOffset),
                subnetMaskString,
                (int)networkAddress,
                (int)broadcastAddress,
                reservedIpsStart,
                maxHostsPerSubnet
        );
    }

    /**
     * Converts long IP address to string format
     * @param ip IP address as long value
     * @return IP address in dotted decimal notation
     */
    private String longToIP(long ip) {
        return String.format("%d.%d.%d.%d",
                (ip >>> 24) & 0xFF,
                (ip >>> 16) & 0xFF,
                (ip >>> 8) & 0xFF,
                ip & 0xFF);
    }

    /**
     * Allocates an IP address for the given VLAN ID
     * @param vlanId The VLAN ID to allocate IP for
     * @return Allocated IP address as string
     */
    public String allocateIP(int vlanId) {
        validateVlanId(vlanId);

        poolLock.readLock().lock();
        try {
            VlanSubnet subnet = getOrCreateSubnet(vlanId);
            return subnet.allocateIP();
        } finally {
            poolLock.readLock().unlock();
        }
    }

    /**
     * Releases an IP address back to the pool
     * @param ip The IP address to release
     * @param vlanId The VLAN ID the IP belongs to
     */
    public void releaseIP(String ip, int vlanId) {
        validateVlanId(vlanId);

        if (ip == null || !isValidIPForVlan(ip, vlanId)) {
            return;
        }

        poolLock.readLock().lock();
        try {
            VlanSubnet subnet = vlanSubnets.get(vlanId);
            if (subnet != null) {
                subnet.releaseIP(ip);
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }

    /**
     * Checks if an IP address is currently in use
     * @param ip The IP address to check
     * @param vlanId The VLAN ID to check in
     * @return true if IP is in use, false otherwise
     */
    public boolean isIPInUse(String ip, int vlanId) {
        validateVlanId(vlanId);

        if (ip == null || !isValidIPForVlan(ip, vlanId)) {
            return false;
        }

        poolLock.readLock().lock();
        try {
            VlanSubnet subnet = vlanSubnets.get(vlanId);
            return subnet != null && subnet.isIPInUse(ip);
        } finally {
            poolLock.readLock().unlock();
        }
    }

    /**
     * Gets the gateway IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Gateway IP address
     */
    public String getGatewayIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).gatewayIP;
    }

    /**
     * Gets the primary DNS server IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Primary DNS server IP address
     */
    public String getDNSServerIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).primaryDnsIP;
    }

    /**
     * Gets the secondary DNS server IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Secondary DNS server IP address
     */
    public String getSecondaryDNSServerIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).secondaryDnsIP;
    }

    /**
     * Gets the subnet mask string
     * @return Subnet mask in dotted decimal notation
     */
    public String getSubnetMask() {
        return subnetMaskString;
    }

    /**
     * Gets the network IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Network IP address
     */
    public String getNetworkIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).networkIP;
    }

    /**
     * Gets the broadcast IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Broadcast IP address
     */
    public String getBroadcastIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).broadcastIP;
    }

    /**
     * Gets the server identifier IP address for the given VLAN
     * @param vlanId The VLAN ID
     * @return Server identifier IP address (same as gateway)
     */
    public String getServerIdentifierIP(int vlanId) {
        return getGatewayIP(vlanId);
    }

    /**
     * Returns the number of used IPs for the given VLAN
     * @param vlanId The VLAN ID
     * @return Number of used IP addresses
     */
    public int getUsedIPCount(int vlanId) {
        validateVlanId(vlanId);

        poolLock.readLock().lock();
        try {
            VlanSubnet subnet = vlanSubnets.get(vlanId);
            return subnet != null ? subnet.getUsedIPCount() : 0;
        } finally {
            poolLock.readLock().unlock();
        }
    }

    /**
     * Returns the number of available IPs for the given VLAN
     * @param vlanId The VLAN ID
     * @return Number of available IP addresses
     */
    public int getAvailableIPCount(int vlanId) {
        validateVlanId(vlanId);

        poolLock.readLock().lock();
        try {
            VlanSubnet subnet = vlanSubnets.get(vlanId);
            return subnet != null ? subnet.getAvailableIPCount() : maxHostsPerSubnet;
        } finally {
            poolLock.readLock().unlock();
        }
    }

    /**
     * Returns statistics for all active VLANs
     * @return VlanPoolStatistics object containing all VLAN statistics
     */
    public VlanPoolStatistics getAllStatistics() {
        poolLock.readLock().lock();
        try {
            VlanPoolStatistics stats = new VlanPoolStatistics();

            vlanSubnets.forEach((vlanId, subnet) -> {
                VlanStatistics vlanStats = new VlanStatistics();
                SubnetInfo subnetInfo = calculateSubnetForVlan(vlanId);

                vlanStats.vlanId = vlanId;
                vlanStats.networkIP = subnetInfo.networkIP;
                vlanStats.gatewayIP = subnetInfo.gatewayIP;
                vlanStats.dnsServerIP = subnetInfo.primaryDnsIP;
                vlanStats.broadcastIP = subnetInfo.broadcastIP;
                vlanStats.subnetMask = subnetInfo.subnetMask;
                vlanStats.usedIPs = subnet.getUsedIPCount();
                vlanStats.availableIPs = subnet.getAvailableIPCount();
                vlanStats.utilizationPercent = (double) vlanStats.usedIPs / maxHostsPerSubnet * 100;

                stats.vlanStatistics.put(vlanId, vlanStats);
                stats.totalUsedIPs += vlanStats.usedIPs;
                stats.totalAvailableIPs += vlanStats.availableIPs;
            });

            stats.activeVlanCount = vlanSubnets.size();
            return stats;
        } finally {
            poolLock.readLock().unlock();
        }
    }

    // Private helper methods

    /**
     * Gets or creates a subnet for the given VLAN ID
     * @param vlanId The VLAN ID
     * @return VlanSubnet object for the VLAN
     */
    private VlanSubnet getOrCreateSubnet(int vlanId) {
        VlanSubnet subnet = vlanSubnets.get(vlanId);
        if (subnet == null) {
            poolLock.readLock().unlock();
            poolLock.writeLock().lock();
            try {
                // Double-check pattern
                subnet = vlanSubnets.get(vlanId);
                if (subnet == null) {
                    SubnetInfo subnetInfo = calculateSubnetForVlan(vlanId);
                    subnet = new VlanSubnet(vlanId, subnetInfo);
                    vlanSubnets.put(vlanId, subnet);
                }
                poolLock.readLock().lock();
            } finally {
                poolLock.writeLock().unlock();
            }
        }
        return subnet;
    }

    /**
     * Validates VLAN ID range
     * @param vlanId The VLAN ID to validate
     * @throws IllegalArgumentException if VLAN ID is out of range
     */
    private void validateVlanId(int vlanId) {
        if (vlanId < MIN_VLAN || vlanId > MAX_VLAN) {
            throw new IllegalArgumentException("VLAN ID must be between " + MIN_VLAN + " and " + MAX_VLAN + ", got: " + vlanId);
        }
    }

    /**
     * Validates if VLAN can be supported with current configuration
     * @param vlanId The VLAN ID to validate
     * @throws RuntimeException if VLAN exceeds capacity
     */
    private void validateVlanCapacity(int vlanId) {
        int maxSupportedVlans = calculateMaxSupportedVlans();
        if (vlanId > maxSupportedVlans) {
            throw new RuntimeException(String.format(
                    "VLAN %d exceeds maximum supported VLANs (%d) for current configuration. " +
                            "Base network: %s/%d can support maximum %d VLANs. " +
                            "Consider using a smaller subnet mask (larger host bits) or different base network.",
                    vlanId, maxSupportedVlans, baseNetworkIP, subnetMaskBits, maxSupportedVlans
            ));
        }
    }

    /**
     * Calculates the maximum number of VLANs that can be supported with current configuration
     * @return Maximum number of supported VLANs
     */
    private int calculateMaxSupportedVlans() {
        // Address space from base network to end of IPv4
        long baseNetworkLong = ((long)baseOctets[0] << 24) |
                ((long)baseOctets[1] << 16) |
                ((long)baseOctets[2] << 8) |
                baseOctets[3];

        long subnetSize = 1L << hostBits;
        long availableSpace = 0x100000000L - (baseNetworkLong & 0xFFFFFFFFL);
        int maxVlans = (int)(availableSpace / subnetSize);

        // Cannot exceed 4094 (VLAN ID limit)
        return Math.min(maxVlans, MAX_VLAN);
    }

    /**
     * Checks if IP address is valid for the given VLAN
     * @param ip The IP address to validate
     * @param vlanId The VLAN ID to check against
     * @return true if IP is valid for VLAN, false otherwise
     */
    private boolean isValidIPForVlan(String ip, int vlanId) {
        if (ip == null) return false;

        try {
            SubnetInfo subnetInfo = calculateSubnetForVlan(vlanId);
            long ipLong = ipToLong(ip);
            long networkLong = ipToLong(subnetInfo.networkIP);
            long broadcastLong = ipToLong(subnetInfo.broadcastIP);

            return ipLong >= networkLong && ipLong <= broadcastLong;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts IP address string to long value
     * @param ip IP address in dotted decimal notation
     * @return IP address as long value
     */
    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = result << 8 | Integer.parseInt(parts[i]);
        }
        return result;
    }

    /**
     * Clears the specified VLAN subnet
     * @param vlanId The VLAN ID to clear
     */
    public void clearVlan(int vlanId) {
        validateVlanId(vlanId);

        poolLock.writeLock().lock();
        try {
            vlanSubnets.remove(vlanId);
        } finally {
            poolLock.writeLock().unlock();
        }
    }

    /**
     * Clears all VLAN subnets
     */
    public void clearAll() {
        poolLock.writeLock().lock();
        try {
            vlanSubnets.clear();
        } finally {
            poolLock.writeLock().unlock();
        }
    }

    // Inner classes

    /**
     * Subnet information class
     */
    private static class SubnetInfo {
        final String networkIP;
        final String broadcastIP;
        final String gatewayIP;
        final String primaryDnsIP;
        final String secondaryDnsIP;
        final String subnetMask;
        final int networkAddressInt;
        final int broadcastAddressInt;
        final int reservedStart;
        final int maxHosts;

        /**
         * Constructor for SubnetInfo
         * @param networkIP Network IP address
         * @param broadcastIP Broadcast IP address
         * @param gatewayIP Gateway IP address
         * @param primaryDnsIP Primary DNS server IP
         * @param secondaryDnsIP Secondary DNS server IP
         * @param subnetMask Subnet mask
         * @param networkAddressInt Network address as integer
         * @param broadcastAddressInt Broadcast address as integer
         * @param reservedStart Start of reserved IP range
         * @param maxHosts Maximum number of hosts
         */
        SubnetInfo(String networkIP, String broadcastIP, String gatewayIP,
                   String primaryDnsIP, String secondaryDnsIP, String subnetMask,
                   int networkAddressInt, int broadcastAddressInt,
                   int reservedStart, int maxHosts) {
            this.networkIP = networkIP;
            this.broadcastIP = broadcastIP;
            this.gatewayIP = gatewayIP;
            this.primaryDnsIP = primaryDnsIP;
            this.secondaryDnsIP = secondaryDnsIP;
            this.subnetMask = subnetMask;
            this.networkAddressInt = networkAddressInt;
            this.broadcastAddressInt = broadcastAddressInt;
            this.reservedStart = reservedStart;
            this.maxHosts = maxHosts;
        }
    }

    /**
     * Class that manages a single VLAN subnet
     */
    private static class VlanSubnet {
        private final int vlanId;
        private final BitSet ipPool;
        private final SubnetInfo subnetInfo;
        private final int usableIPStart;
        private final int usableIPCount;

        /**
         * Constructor for VlanSubnet
         * @param vlanId The VLAN ID
         * @param subnetInfo Subnet information
         */
        public VlanSubnet(int vlanId, SubnetInfo subnetInfo) {
            this.vlanId = vlanId;
            this.subnetInfo = subnetInfo;

            // Calculate usable IP range (skip reserved IPs)
            this.usableIPStart = subnetInfo.reservedStart;
            this.usableIPCount = subnetInfo.maxHosts - subnetInfo.reservedStart + 1;
            this.ipPool = new BitSet(usableIPCount);
        }

        /**
         * Allocates the next available IP address
         * @return Allocated IP address as string
         * @throws RuntimeException if IP pool is exhausted
         */
        public synchronized String allocateIP() {
            int nextAvailable = ipPool.nextClearBit(0);
            if (nextAvailable >= usableIPCount) {
                throw new RuntimeException("IP pool exhausted for VLAN " + vlanId +
                        " - all " + usableIPCount + " usable IPs allocated");
            }

            ipPool.set(nextAvailable);

            // Convert index to IP address
            int hostOffset = usableIPStart + nextAvailable;
            long ipAddress = subnetInfo.networkAddressInt + hostOffset;

            return String.format("%d.%d.%d.%d",
                    (ipAddress >>> 24) & 0xFF,
                    (ipAddress >>> 16) & 0xFF,
                    (ipAddress >>> 8) & 0xFF,
                    ipAddress & 0xFF);
        }

        /**
         * Releases an IP address back to the pool
         * @param ip The IP address to release
         */
        public synchronized void releaseIP(String ip) {
            try {
                String[] parts = ip.split("\\.");
                long ipLong = 0;
                for (int i = 0; i < 4; i++) {
                    ipLong = ipLong << 8 | Integer.parseInt(parts[i]);
                }

                int hostOffset = (int)(ipLong - subnetInfo.networkAddressInt);
                int index = hostOffset - usableIPStart;

                if (index >= 0 && index < usableIPCount) {
                    ipPool.clear(index);
                }
            } catch (Exception e) {
                logger.error("Failed to release IP for VLAN {}: {} - {}", vlanId, ip, e.getMessage());
            }
        }

        /**
         * Checks if an IP address is currently in use
         * @param ip The IP address to check
         * @return true if IP is in use, false otherwise
         */
        public synchronized boolean isIPInUse(String ip) {
            try {
                String[] parts = ip.split("\\.");
                long ipLong = 0;
                for (int i = 0; i < 4; i++) {
                    ipLong = ipLong << 8 | Integer.parseInt(parts[i]);
                }

                int hostOffset = (int)(ipLong - subnetInfo.networkAddressInt);
                int index = hostOffset - usableIPStart;

                return index >= 0 && index < usableIPCount && ipPool.get(index);
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Gets the number of used IP addresses
         * @return Number of used IPs
         */
        public int getUsedIPCount() {
            return ipPool.cardinality();
        }

        /**
         * Gets the number of available IP addresses
         * @return Number of available IPs
         */
        public int getAvailableIPCount() {
            return usableIPCount - ipPool.cardinality();
        }
    }

    /**
     * VLAN statistics class
     */
    public static class VlanStatistics {
        public int vlanId;
        public String networkIP;
        public String gatewayIP;
        public String dnsServerIP;
        public String broadcastIP;
        public String subnetMask;
        public int usedIPs;
        public int availableIPs;
        public double utilizationPercent;

        @Override
        public String toString() {
            return String.format("VLAN %d: %s/%s, Gateway: %s, DNS: %s, Used: %d/%d (%.1f%%)",
                    vlanId, networkIP, subnetMask, gatewayIP, dnsServerIP, usedIPs,
                    usedIPs + availableIPs, utilizationPercent);
        }
    }

    /**
     * All VLAN pool statistics
     */
    public static class VlanPoolStatistics {
        public int activeVlanCount;
        public int totalUsedIPs;
        public int totalAvailableIPs;
        public final ConcurrentHashMap<Integer, VlanStatistics> vlanStatistics = new ConcurrentHashMap<>();

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Active VLANs: %d, Total IPs: %d used / %d available\n",
                    activeVlanCount, totalUsedIPs, totalAvailableIPs));

            vlanStatistics.values().forEach(stats ->
                    sb.append("  ").append(stats.toString()).append("\n"));

            return sb.toString();
        }
    }
}