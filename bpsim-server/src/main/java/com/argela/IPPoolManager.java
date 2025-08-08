package com.argela;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.BitSet;

@ApplicationScoped
public class IPPoolManager {
    private final BitSet ipPool = new BitSet(245); // 192.168.1.10-254 range (245 IP)
    private static final int START_IP = 10;
    private static final int END_IP = 254;
    private static final String IP_BASE = "192.168.1.";

    public synchronized String allocateIP() {
        // İlk boş IP'yi bul - O(1) amortized
        int nextAvailable = ipPool.nextClearBit(0);

        if (nextAvailable >= (END_IP - START_IP + 1)) {
            throw new RuntimeException("IP pool exhausted - all IPs allocated");
        }

        ipPool.set(nextAvailable); // Mark as used
        return IP_BASE + (START_IP + nextAvailable);
    }

    public synchronized void releaseIP(String ip) {
        if (ip == null || !ip.startsWith(IP_BASE)) {
            return; // Invalid IP format
        }

        try {
            String[] parts = ip.split("\\.");
            int lastOctet = Integer.parseInt(parts[3]);
            int index = lastOctet - START_IP;

            if (index >= 0 && index < ipPool.size()) {
                ipPool.clear(index); // Mark as available
            }
        } catch (Exception e) {
            System.err.println("Failed to release IP: " + ip + " - " + e.getMessage());
        }
    }

    public synchronized boolean isIPInUse(String ip) {
        if (ip == null || !ip.startsWith(IP_BASE)) {
            return false;
        }

        try {
            String[] parts = ip.split("\\.");
            int lastOctet = Integer.parseInt(parts[3]);
            int index = lastOctet - START_IP;

            return index >= 0 && index < ipPool.size() && ipPool.get(index);
        } catch (Exception e) {
            return false;
        }
    }

    public int getUsedIPCount() {
        return ipPool.cardinality();
    }

    public int getAvailableIPCount() {
        return (END_IP - START_IP + 1) - ipPool.cardinality();
    }
}