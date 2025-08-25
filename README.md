# BPSIM - DHCP Simulation Tool

## Docker ile Çalıştırma

### Hızlı Başlangıç

#### 1. Docker Image Build Etme
```bash
# Proje dizininde
docker build -t bpsim:latest .
```

#### 2. Container'ı Çalıştırma
```bash
# Port mapping ile çalıştırma
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  bpsim:latest
```

#### 3. Erişim
- **Web Arayüzü**: http://localhost:8080
- **gRPC Server**: localhost:9000
- **REST API**: http://localhost:8080/dhcp

### CLI Aracı Kullanımı

Container içinde CLI aracını kullanmak için:

```bash
# Container'a bağlanma
docker exec -it bpsim-container bash

# CLI komutları
bpsimctl --help
bpsimctl list --help
bpsimctl dhcp discovery 0 1 0 1024 100
bpsimctl list -v 100
bpsimctl storm 100
```

### Port Açıklamaları

| Port | Protokol | Açıklama |
|------|----------|----------|
| 8080 | HTTP | Web arayüzü ve REST API |
| 9000 | gRPC | DHCP simülasyon gRPC servisi |


### Konfigürasyon

Container çalışırken konfigürasyon değişiklikleri için environment variables kullanılabilir:

```bash
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  -e DHCP_DEVICE_MAX_COUNT=50000 \
  -e DHCP_PON_PORT_COUNT=32 \
  -e DHCP_ONU_PORT_COUNT=128 \
  bpsim:latest
```

### Monitoring

Container resource kullanımını izleme:

```bash
# Gerçek zamanlı resource kullanımı
docker stats bpsim-container

# Memory ve CPU kullanımı
docker exec bpsim-container top
```

### Backup ve Restore

Veriler container restart edildiğinde kaybolur. Kalıcı veri için volume kullanın:

```bash
docker run -d \
  --name bpsim-container \
  -p 8080:8080 \
  -p 9000:9000 \
  -v bpsim-data:/bpsim/data \
  bpsim:latest
```

## CLI Komut Örnekleri

### Temel DHCP Komutları
```bash
# DHCP Discovery
bpsimctl dhcp discovery 0 1 0 1024 100

# DHCP Offer
bpsimctl dhcp offer 0 1 0 1024 100

# DHCP Request
bpsimctl dhcp request 0 1 0 1024 100

# DHCP ACK
bpsimctl dhcp ack 0 1 0 1024 100
```

### Liste ve Filtreleme
```bash
# Tüm cihazları listele
bpsimctl list

# VLAN bazlı filtreleme
bpsimctl list -v 100

# PON port bazlı filtreleme
bpsimctl list -p 0

# State bazlı filtreleme
bpsimctl list -s ACKNOWLEDGED

# Genel text filtresi
bpsimctl list -f "10.0.1"

# Geniş çıktı (tüm kolonlar)
bpsimctl list -w
```

### Storm Simülasyonu
```bash
# Rate bazlı storm (100 cihaz/saniye)
bpsimctl storm 100

# Interval bazlı storm (5 saniyede bir cihaz)
bpsimctl storm 0 5.0
```