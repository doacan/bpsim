package com.argela;

import java.time.Instant;

public class DeviceInfo {
    private int id; // 1, 2, 3, ...
    private String clientMac;
    private String ipAddress;
    private String requiredIp;
    private String state;
    private String dns;
    private String gateway;
    private String serverIdentifier;
    private String subnetMask;
    private int xid;
    private long leaseTime; // in seconds
    private int vlanId;
    private int ponPort;
    private int gemPort;
    private int uniId;
    private int onuId;
    private Instant leaseStartTime;
    private Instant dhcpStartTime;
    private Instant dhcpCompletionTime;

    // Constructor
    public DeviceInfo(int id, String clientMac, String ipAddress, String requiredIp, String state,
                      String dns, String gateway, String serverIdentifier, String subnetMask,
                      int xid, long leaseTime, int vlanId, int ponPort, int gemPort,
                      int uniId, int onuId, Instant leaseStartTime) {
        this.id = id;
        this.clientMac = clientMac;
        this.ipAddress = ipAddress;
        this.requiredIp = requiredIp;
        this.state = state;
        this.dns = dns;
        this.gateway = gateway;
        this.serverIdentifier = serverIdentifier;
        this.subnetMask = subnetMask;
        this.xid = xid;
        this.leaseTime = leaseTime;
        this.vlanId = vlanId;
        this.ponPort = ponPort;
        this.gemPort = gemPort;
        this.uniId = uniId;
        this.onuId = onuId;
        this.leaseStartTime = leaseStartTime;
        this.dhcpStartTime = Instant.now();
        this.dhcpCompletionTime = null;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getClientMac() { return clientMac; }
    public void setClientMac(String clientMac) { this.clientMac = clientMac; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getRequiredIp() { return requiredIp; }
    public void setRequiredIp(String requiredIp) { this.requiredIp = requiredIp; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDns() { return dns; }
    public void setDns(String dns) { this.dns = dns; }

    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }

    public String getServerIdentifier() { return serverIdentifier; }
    public void setServerIdentifier(String serverIdentifier) { this.serverIdentifier = serverIdentifier; }

    public String getSubnetMask() { return subnetMask; }
    public void setSubnetMask(String subnetMask) { this.subnetMask = subnetMask; }

    public int getXid() { return xid; }
    public void setXid(int xid) { this.xid = xid; }

    public long getLeaseTime() { return leaseTime; }
    public void setLeaseTime(long leaseTime) { this.leaseTime = leaseTime; }

    public int getVlanId() { return vlanId; }
    public void setVlanId(int vlanId) { this.vlanId = vlanId; }

    public int getPonPort() { return ponPort; }
    public void setPonPort(int ponPort) { this.ponPort = ponPort; }

    public int getGemPort() { return gemPort; }
    public void setGemPort(int gemPort) { this.gemPort = gemPort; }

    public int getUniId() { return uniId; }
    public void setUniId(int uniId) { this.uniId = uniId; }

    public int getOnuId() { return onuId; }
    public void setOnuId(int onuId) { this.onuId = onuId; }

    public Instant getLeaseStartTime() { return leaseStartTime; }
    public void setLeaseStartTime(Instant leaseStartTime) { this.leaseStartTime = leaseStartTime; }

    public Instant getDhcpStartTime() { return dhcpStartTime; }
    public void setDhcpStartTime(Instant dhcpStartTime) { this.dhcpStartTime = dhcpStartTime; }

    public Instant getDhcpCompletionTime() { return dhcpCompletionTime; }
    public void setDhcpCompletionTime(Instant dhcpCompletionTime) { this.dhcpCompletionTime = dhcpCompletionTime; }

    public long getDhcpDurationMs() {
        if (dhcpStartTime == null) return 0;
        return java.time.Duration.between(dhcpStartTime, Instant.now()).toMillis();
    }

    public boolean isDhcpCompleted() {
        return "ACKNOWLEDGED".equals(state);
    }

    public Long getDhcpCompletionTimeMs() {
        if (!isDhcpCompleted() || dhcpStartTime == null) return null;

        if (dhcpCompletionTime != null) {
            // If completion time is set, use it
            return java.time.Duration.between(dhcpStartTime, dhcpCompletionTime).toMillis();
        }

        // For backward compatibility
        return java.time.Duration.between(dhcpStartTime, Instant.now()).toMillis();
    }

}
