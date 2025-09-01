# BPSIM - DHCP Simulation Tool

## Docker Deployment

### Quick Start

#### 1. Build Docker Image
```bash
# In project directory
docker build -t bpsim:latest .
```

#### 2. Run Container
```bash
# Run with port mapping
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  bpsim:latest
```

#### 3. Access Services
- **Web Dashboard**: http://localhost:8080
- **gRPC Server**: localhost:9000
- **REST API**: http://localhost:8080/dhcp

### CLI Tool Usage

Use the CLI tool inside the container:

```bash
# Connect to container
docker exec -it bpsim-container bash

# CLI commands
bpsimctl --help
bpsimctl list --help
bpsimctl dhcp discovery 0 1 0 1024 100
bpsimctl list -v 100
bpsimctl storm 100
```

### Port Mapping

| Port | Protocol | Description |
|------|----------|-------------|
| 8080 | HTTP | Web dashboard and REST API |
| 9000 | gRPC | DHCP simulation gRPC service |

### Environment Configuration

Customize configuration using environment variables:

```bash
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  -e DHCP_PON_PORT_COUNT=32 \
  -e DHCP_ONU_PORT_COUNT=128 \
  -e DHCP_NETWORK_BASE_IP=192.168.0.0 \
  -e DHCP_SUBNET_MASK_BITS=24 \
  bpsim:latest
```

### Persistent Data

For persistent data across container restarts, use volumes:

```bash
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  -v bpsim-data:/bpsim/data \
  bpsim:latest
```

### Monitoring

Monitor container resource usage:

```bash
# Real-time resource usage
docker stats bpsim-container

# Memory and CPU usage
docker exec bpsim-container top
```

## CLI Command Reference

### Basic DHCP Commands

Simulate individual DHCP packet types:

```bash
# DHCP Discovery packet
bpsimctl dhcp discovery <pon_port> <onu_id> <uni_id> <gem_port> <c_tag>
bpsimctl dhcp discovery 0 1 0 1024 100

# DHCP Offer packet
bpsimctl dhcp offer 0 1 0 1024 100

# DHCP Request packet
bpsimctl dhcp request 0 1 0 1024 100

# DHCP ACK packet
bpsimctl dhcp ack 0 1 0 1024 100

# With custom MAC address
bpsimctl dhcp discovery 0 1 0 1024 100 --mac "aa:bb:cc:dd:ee:ff"
```

### Device Listing and Filtering

List and filter DHCP devices:

```bash
# List all devices
bpsimctl list

# Filter by VLAN ID
bpsimctl list -v 100
bpsimctl list --vlan 100

# Filter by PON port
bpsimctl list -p 0
bpsimctl list --pon 0

# Filter by ONU ID
bpsimctl list -o 1
bpsimctl list --onu 1

# Filter by UNI ID
bpsimctl list -u 0
bpsimctl list --uni 0

# Filter by GEM port
bpsimctl list -g 1024
bpsimctl list --gem 1024

# Filter by device state
bpsimctl list -s ACKNOWLEDGED
bpsimctl list --state IDLE

# General text filter (searches across multiple fields)
bpsimctl list -f "10.0.1"
bpsimctl list --filter "ACKNOWLEDGED"

# Wide output (show all columns)
bpsimctl list -w
bpsimctl list --wide

# Combine multiple filters
bpsimctl list -v 100 -s ACKNOWLEDGED -p 0
```

### Storm Simulation

Generate high-volume DHCP requests:

```bash
# Rate-based storm (devices per second)
bpsimctl storm 100        # 100 devices/second
bpsimctl storm 50         # 50 devices/second

# Interval-based storm (seconds between devices)
bpsimctl storm 0 5.0      # One device every 5 seconds
bpsimctl storm 0 0.1      # One device every 100ms

# Stop running storm
bpsimctl stop
```

### System Management

Manage system state and configuration:

```bash
# Get system information
bpsimctl info

# Reset all devices to IDLE state (keeps devices)
bpsimctl reset

# Reload all devices (clear and recreate)
bpsimctl reload

# Clear all devices from system
bpsimctl clear
```

### Server URL Configuration

Specify custom server URL:

```bash
# Use different server
bpsimctl list --url http://192.168.1.100:8080
bpsimctl storm 100 --url http://remote-server:8080

# Short form
bpsimctl list -U http://localhost:9090
```

## REST API Reference

### DHCP Simulation

#### Send DHCP Packet
```bash
POST /dhcp
Content-Type: application/json

{
  "packetType": "DISCOVERY",
  "ponPort": 0,
  "onuId": 1,
  "uniId": 0,
  "gemPort": 1024,
  "cTag": 100,
  "clientMac": "aa:bb:cc:dd:ee:ff"  // optional
}
```

#### List DHCP Sessions
```bash
# List all devices
GET /dhcp/list

# With filters
GET /dhcp/list?vlanId=100&state=ACKNOWLEDGED&ponPort=0
```

#### Start DHCP Storm
```bash
POST /dhcp/storm
Content-Type: application/json

# Rate-based
{
  "rate": 100
}

# Interval-based
{
  "intervalSec": 5.0
}
```

#### Cancel Storm
```bash
POST /dhcp/storm/cancel
```

### System Management

#### Get System Information
```bash
GET /dhcp/info
```

#### Reset Devices
```bash
POST /dhcp/reset
```

#### Reload Devices
```bash
POST /dhcp/reload
```

#### Clear All Devices
```bash
DELETE /dhcp/clear-all
```
