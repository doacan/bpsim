package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@ApplicationScoped
public class VlanIPPoolManager {
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

    // VLAN aralığı: 1-4094
    private static final int MIN_VLAN = 1;
    private static final int MAX_VLAN = 4094;

    // Her VLAN için IP pool'u (254 IP per VLAN, 2-255)
    private final ConcurrentHashMap<Integer, VlanSubnet> vlanSubnets = new ConcurrentHashMap<>();
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock();

    private int[] baseOctets;
    private int subnetMask;
    private int hostBits;
    private int maxHostsPerSubnet;
    private String subnetMaskString;

    @PostConstruct
    public void initializePools() {
        parseBaseNetwork();
        calculateSubnetParameters();
        int maxSupportedVlans = calculateMaxSupportedVlans();

        if (maxSupportedVlans < MAX_VLAN) {
            System.out.println("  WARNING: Current configuration supports only " + maxSupportedVlans +
                    " VLANs, but system allows up to " + MAX_VLAN);
            System.out.println("  Consider using a smaller subnet mask or different base network");
        }

    }

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

    private void calculateSubnetParameters() {
        if (subnetMaskBits < 8 || subnetMaskBits > 30) {
            throw new IllegalArgumentException("Subnet mask bits must be between 8 and 30");
        }

        // Subnet mask hesaplama
        subnetMask = (0xFFFFFFFF << (32 - subnetMaskBits)) & 0xFFFFFFFF;
        hostBits = 32 - subnetMaskBits;
        maxHostsPerSubnet = (1 << hostBits) - 2; // Network ve broadcast hariç

        // Subnet mask string oluşturma
        int mask = subnetMask;
        subnetMaskString = String.format("%d.%d.%d.%d",
                (mask >>> 24) & 0xFF,
                (mask >>> 16) & 0xFF,
                (mask >>> 8) & 0xFF,
                mask & 0xFF);
    }

    /**
     * VLAN ID'ye göre subnet hesaplama
     */
    private SubnetInfo calculateSubnetForVlan(int vlanId) {
        validateVlanId(vlanId);
        validateVlanCapacity(vlanId);

        // Base network'ü 32-bit integer'a çevir
        long baseNetworkLong = ((long)baseOctets[0] << 24) |
                ((long)baseOctets[1] << 16) |
                ((long)baseOctets[2] << 8) |
                baseOctets[3];

        // VLAN ID'ye göre subnet offset hesaplama
        long subnetSize = 1L << hostBits;
        long subnetOffset = (vlanId - 1) * subnetSize;
        long networkAddress = baseNetworkLong + subnetOffset;

        // Overflow kontrolü - IPv4 aralığını aşmamalı
        if (networkAddress > 0xFFFFFFFFL) {
            throw new RuntimeException(String.format(
                    "VLAN %d subnet address exceeds IPv4 range. " +
                            "Base network: %s, Subnet bits: %d, Max supported VLANs: %d",
                    vlanId, baseNetworkIP, subnetMaskBits, calculateMaxSupportedVlans()
            ));
        }

        // Network ve broadcast adresleri hesaplama
        long broadcastAddress = networkAddress + subnetSize - 1;

        // Broadcast da IPv4 aralığını aşmamalı
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

    private String longToIP(long ip) {
        return String.format("%d.%d.%d.%d",
                (ip >>> 24) & 0xFF,
                (ip >>> 16) & 0xFF,
                (ip >>> 8) & 0xFF,
                ip & 0xFF);
    }

    /**
     * VLAN ID'ye göre IP adresi ayırır
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
     * IP adresini serbest bırakır
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
     * IP'nin kullanımda olup olmadığını kontrol eder
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

    // Network configuration methods
    public String getGatewayIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).gatewayIP;
    }

    public String getDNSServerIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).primaryDnsIP;
    }

    public String getSecondaryDNSServerIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).secondaryDnsIP;
    }

    public String getSubnetMask() {
        return subnetMaskString;
    }

    public String getNetworkIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).networkIP;
    }

    public String getBroadcastIP(int vlanId) {
        return calculateSubnetForVlan(vlanId).broadcastIP;
    }

    public String getServerIdentifierIP(int vlanId) {
        return getGatewayIP(vlanId);
    }

    /**
     * VLAN için kullanılan IP sayısını döndürür
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
     * VLAN için kullanılabilir IP sayısını döndürür
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
     * Tüm aktif VLAN'lar için istatistikleri döndürür
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

    private void validateVlanId(int vlanId) {
        if (vlanId < MIN_VLAN || vlanId > MAX_VLAN) {
            throw new IllegalArgumentException("VLAN ID must be between " + MIN_VLAN + " and " + MAX_VLAN + ", got: " + vlanId);
        }
    }

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
     * Mevcut konfigürasyonla desteklenebilecek maksimum VLAN sayısını hesaplar
     */
    private int calculateMaxSupportedVlans() {
        // Base network'ten IPv4 sonuna kadar olan adres alanı
        long baseNetworkLong = ((long)baseOctets[0] << 24) |
                ((long)baseOctets[1] << 16) |
                ((long)baseOctets[2] << 8) |
                baseOctets[3];

        long subnetSize = 1L << hostBits;
        long availableSpace = 0x100000000L - (baseNetworkLong & 0xFFFFFFFFL);
        int maxVlans = (int)(availableSpace / subnetSize);

        // 4094'ten fazla olamaz (VLAN ID limiti)
        return Math.min(maxVlans, MAX_VLAN);
    }

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

    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = result << 8 | Integer.parseInt(parts[i]);
        }
        return result;
    }

    /**
     * Belirli VLAN'ı temizler
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
     * Tüm VLAN'ları temizler
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
     * Subnet bilgileri sınıfı
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
     * Tek bir VLAN subnet'ini yöneten sınıf
     */
    private static class VlanSubnet {
        private final int vlanId;
        private final BitSet ipPool;
        private final SubnetInfo subnetInfo;
        private final int usableIPStart;
        private final int usableIPCount;

        public VlanSubnet(int vlanId, SubnetInfo subnetInfo) {
            this.vlanId = vlanId;
            this.subnetInfo = subnetInfo;

            // Kullanılabilir IP aralığını hesapla (reserved IP'leri atla)
            this.usableIPStart = subnetInfo.reservedStart;
            this.usableIPCount = subnetInfo.maxHosts - subnetInfo.reservedStart + 1;
            this.ipPool = new BitSet(usableIPCount);
        }

        public synchronized String allocateIP() {
            int nextAvailable = ipPool.nextClearBit(0);
            if (nextAvailable >= usableIPCount) {
                throw new RuntimeException("IP pool exhausted for VLAN " + vlanId +
                        " - all " + usableIPCount + " usable IPs allocated");
            }

            ipPool.set(nextAvailable);

            // Index'i IP adresine çevir
            int hostOffset = usableIPStart + nextAvailable;
            long ipAddress = subnetInfo.networkAddressInt + hostOffset;

            return String.format("%d.%d.%d.%d",
                    (ipAddress >>> 24) & 0xFF,
                    (ipAddress >>> 16) & 0xFF,
                    (ipAddress >>> 8) & 0xFF,
                    ipAddress & 0xFF);
        }

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
                System.err.println("Failed to release IP for VLAN " + vlanId + ": " + ip + " - " + e.getMessage());
            }
        }

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

        public int getUsedIPCount() {
            return ipPool.cardinality();
        }

        public int getAvailableIPCount() {
            return usableIPCount - ipPool.cardinality();
        }
    }

    /**
     * VLAN istatistikleri sınıfı
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
     * Tüm VLAN pool istatistikleri
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