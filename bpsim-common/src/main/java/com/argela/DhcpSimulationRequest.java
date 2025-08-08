package com.argela;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

public class DhcpSimulationRequest implements Serializable {
    @JsonProperty("packetType")
    private String packetType;

    @JsonProperty("ponPort")
    private int ponPort;

    @JsonProperty("onuId")
    private int onuId;

    @JsonProperty("uniId")
    private int uniId;

    @JsonProperty("gemPort")
    private int gemPort;

    @JsonProperty("cTag")
    private int cTag;

    public DhcpSimulationRequest() {
    }

    public DhcpSimulationRequest(String packetType, int ponPort, int onuId, int uniId, int gemPort, int cTag) {
        this.packetType = packetType;
        this.ponPort = ponPort;
        this.onuId = onuId;
        this.uniId = uniId;
        this.gemPort = gemPort;
        this.cTag = cTag;
    }

    public String getPacketType() {
        return packetType;
    }

    public void setPacketType(String packetType) {
        this.packetType = packetType;
    }

    public int getPonPort() {
        return ponPort;
    }

    public void setPonPort(int ponPort) {
        this.ponPort = ponPort;
    }

    public int getOnuId() {
        return onuId;
    }

    public void setOnuId(int onuId) {
        this.onuId = onuId;
    }

    public int getUniId() {
        return uniId;
    }

    public void setUniId(int uniId) {
        this.uniId = uniId;
    }

    public int getGemPort() {
        return gemPort;
    }

    public void setGemPort(int gemPort) {
        this.gemPort = gemPort;
    }

    public int getCTag() {
        return cTag;
    }

    public void setCTag(int cTag) {
        this.cTag = cTag;
    }

    @Override
    public String toString() {
        return "DhcpSimulationRequest{" +
                "packetType='" + packetType + '\'' +
                ", ponPort=" + ponPort +
                ", onuId=" + onuId +
                ", uniId=" + uniId +
                ", gemPort=" + gemPort +
                ", cTag=" + cTag +
                '}';
    }
}
