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
    private final ConcurrentHashMap<Integer, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final AtomicInteger deviceIdCounter = new AtomicInteger(0);
    private final AtomicInteger xidCounter = new AtomicInteger(new Random().nextInt(1000000));
    private final ThreadLocal<Random> localRandom = ThreadLocal.withInitial(Random::new);
    private final Set<String> macAddresses = ConcurrentHashMap.newKeySet();
    private final Set<Integer> xids = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();
    private final ConcurrentHashMap<Integer, DeviceInfo> devicesByXid = new ConcurrentHashMap<>();
    private final static int MAX_DEVICES = 100;

    @Inject
    IPPoolManager ipPoolManager;

    // Yeni cihaz eklerken id otomatik atanacak
    public void addDevice(DeviceInfo device) {
        // Limit kontrolü
        if (devices.size() >= MAX_DEVICES) {
            throw new RuntimeException("Maximum device limit reached: " + MAX_DEVICES);
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

        // Cihazları kaydet
        devices.put(newId, device);
        devicesByXid.put(device.getXid(), device);

        DeviceWebSocket.broadcastDevice(device);
        System.out.println("Device added: ID=" + newId + ", XID=" + device.getXid() +
                ", MAC=" + device.getClientMac());
    }

    public Collection<DeviceInfo> getAllDevices() {
        return devices.values();
    }

    public DeviceInfo getDevice(int id) {
        return devices.get(id);
    }

    public void updateDevice(DeviceInfo device) {
        DeviceInfo existingDevice = devices.get(device.getId());
        if (existingDevice != null) {
            // Eski IP'yi serbest bırak
            if (existingDevice.getIpAddress() != null &&
                    !existingDevice.getIpAddress().equals(device.getIpAddress())) {
                ipPoolManager.releaseIP(existingDevice.getIpAddress());
            }

            // Yeni device'ı kaydet
            devices.put(device.getId(), device);
            devicesByXid.put(device.getXid(), device);

            DeviceWebSocket.broadcastDevice(device);

            System.out.println("Device updated: ID=" + device.getId() +
                    ", XID=" + device.getXid() +
                    ", State=" + device.getState() +
                    ", IP=" + device.getIpAddress());
        }
    }

    /**
     * XID'ye göre cihaz bulur - YENİ METOD
     */
    public Optional<DeviceInfo> findDeviceByXid(int xid) {
        DeviceInfo device = devicesByXid.get(xid);
        return Optional.ofNullable(device);
    }

    /**
     * ID'ye göre cihaz bulur
     */
    public Optional<DeviceInfo> findDeviceById(int id) {
        DeviceInfo device = devices.get(id);
        return Optional.ofNullable(device);
    }

    /**
     * MAC adresine göre cihaz bulur
     */
    public Optional<DeviceInfo> findDeviceByMac(String mac) {
        return devices.values().stream()
                .filter(device -> mac.equals(device.getClientMac()))
                .findFirst();
    }

    /**
     * IP adresinin kullanımda olup olmadığını kontrol eder
     */
    public boolean isIPAddressInUse(String ipAddress) {
        return ipPoolManager.isIPInUse(ipAddress);
    }

    public void removeDevice(int id) {
        DeviceInfo device = devices.remove(id);
        if (device != null) {
            macAddresses.remove(device.getClientMac());
            xids.remove(device.getXid());
            devicesByXid.remove(device.getXid());

            // IP'yi pool'a geri ver
            if (device.getIpAddress() != null) {
                ipPoolManager.releaseIP(device.getIpAddress());
            }

            System.out.println("Device removed: ID=" + id);
        }
    }
    public String generateUniqueIPAddress() {
        return ipPoolManager.allocateIP();
    }

    /**
     * MAC adresinin kullanımda olup olmadığını kontrol eder
     */
    public boolean isMacAddressInUse(String macAddress) {
        return macAddresses.contains(macAddress);
    }

    /**
     * Belirli durumda olan cihazları döndürür
     */
    public List<DeviceInfo> getDevicesByState(String state) {
        return devices.values().stream()
                .filter(device -> state.equals(device.getState()))
                .toList();
    }

    /**
     * Lease süresi dolmuş cihazları temizler
     */
    public void cleanExpiredLeases() {
        Instant now = Instant.now();
        List<DeviceInfo> expiredDevices = devices.values().stream()
                .filter(device -> device.getLeaseStartTime() != null &&
                        device.getLeaseTime() > 0 &&
                        now.isAfter(device.getLeaseStartTime().plusSeconds(device.getLeaseTime())))
                .toList();

        for (DeviceInfo device : expiredDevices) {
            System.out.println("Removing expired lease for device ID: " + device.getId());
            removeDevice(device.getId());
        }
    }

    /**
     * İstatistikleri döndürür
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        Map<String, Long> stateCount = devices.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        DeviceInfo::getState,
                        java.util.stream.Collectors.counting()));

        stats.put("totalDevices", devices.size());
        stats.put("stateCount", stateCount);
        stats.put("usedIPAddresses", ipPoolManager.getUsedIPCount());
        stats.put("availableIPAddresses", ipPoolManager.getAvailableIPCount());
        stats.put("usedMacAddresses", macAddresses.size());
        stats.put("nextDeviceId", deviceIdCounter.get());

        return stats;
    }

    /**
     * Tüm verileri temizler (test için)
     */
    public void clearAll() {
        devices.clear();
        devicesByXid.clear();
        //usedIPAddresses.clear();
        macAddresses.clear();
        xids.clear();
        deviceIdCounter.set(0);
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
}