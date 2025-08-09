package com.argela;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DhcpStormRequest {

    @JsonProperty("rate")
    private Integer rate;

    @JsonProperty("intervalSec")
    private Double intervalSec;

    // Default constructor
    public DhcpStormRequest() {
    }

    // Constructor with parameters
    public DhcpStormRequest(Integer rate, Double intervalSec) {
        this.rate = rate;
        this.intervalSec = intervalSec;
    }

    public Integer getRate() { return rate; }
    public void setRate(Integer rate) { this.rate = rate; }

    public Double getIntervalSec() { return intervalSec; }
    public void setIntervalSec(Double intervalSec) { this.intervalSec = intervalSec; }

    // toString method for debugging
    @Override
    public String toString() {
        return "DhcpStormRequest{" +
                "rate=" + rate +
                ", intervalSec=" + intervalSec +
                '}';
    }

    // Validation helper method
    public boolean isValid() {
        return (rate != null && rate > 0) || (intervalSec != null && intervalSec > 0);
    }
}