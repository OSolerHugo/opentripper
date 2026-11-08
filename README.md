# OpenTripper 🏍️

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_master.png" alt="OpenTripper Logo" width="128" height="128" />
  <br>
  <strong>Open technology for motorcycle instrumentation.</strong>
  <br>
  <em>Open-source automotive instrumentation, telemetry, and navigation application for motorcycles, initially built for the Royal Enfield Hunter 350 and its Tripper Pod.</em>
</p>

<p align="center">
  🌐 <strong>English</strong> | <a href="README.pt-BR.md"><strong>Português (Brasil)</strong></a>
</p>

<p align="center">
  <a href="https://github.com/OSolerHugo/opentripper/releases/latest"><img src="https://img.shields.io/github/v/release/OSolerHugo/opentripper?color=00C2D9&style=for-the-badge&label=Release" alt="Latest Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-22C785?style=for-the-badge" alt="License MIT"></a>
  <a href="#"><img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-141C28?style=for-the-badge&logo=android" alt="Android 8.0+"></a>
  <a href="PROTOCOL.md"><img src="https://img.shields.io/badge/Target-Hunter%20350%20%7C%20Tripper%20Pod-F5A623?style=for-the-badge" alt="Target Hardware"></a>
</p>

---

## ⚡ Quick Download

Download the latest version directly to your Android smartphone. This link is permanent and always points to the newest release signed with the project's permanent keystore (subsequent updates install in-place without signature conflicts):

<p align="center">
  <a href="https://github.com/OSolerHugo/opentripper/releases/latest/download/opentripper.apk">
    <img src="https://img.shields.io/badge/DOWNLOAD-LATEST%20APK-00C2D9?style=for-the-badge&logo=android&logoColor=white" height="42" alt="Download APK" />
  </a>
</p>

> [!NOTE]
> **Installation & Google Play Protect:** Because OpenTripper is distributed directly via GitHub releases (outside the Google Play Store), Google Play Protect may display a prompt stating the app developer is unrecognized. Simply tap **"More details"** ➔ **"Install anyway"**. The project is 100% open-source and auditable.

---

## 🧭 What is OpenTripper?

The secondary **Tripper Pod** display installed on the **Royal Enfield Hunter 350** (and other J-Series platform bikes such as Meteor and Classic 350) operates via an NXP Bluetooth Low Energy (BLE) module advertising as `RE_DISP`. The official proprietary mobile app imposes route restrictions, limited navigation features, and high battery consumption.

**OpenTripper** was built through complete reverse engineering of the BLE protocol to deliver:
1. **Total Navigation Freedom:** Run real Google Maps and Waze turn-by-turn navigation directly on the Tripper Pod screen.
2. **Automotive Cockpit Telemetry:** High-precision GPS digital speedometer, partial trip odometer, compass heading, and elapsed ride time.
3. **Rock-Solid Connection Stability:** Native *Dual-Role GATT Server* engine that eliminates the 5-second connection drop caused by the NXP firmware watchdogs.
4. **Zero-Waste Battery Strategy:** Phone GPS sensor hardware is automatically powered down the instant the motorcycle disconnects.

---

## ✨ Key Features

### 🔄 Hybrid Navigation (Google Maps & Waze Turn-by-Turn)
* **Background Notification Listener:** Seamlessly captures navigation instructions from Google Maps and Waze notifications in real time and converts them into Tripper hardware binary packets.
* **Comprehensive Maneuver Catalog:** 90° turns, slight turns, sharp turns, highway exits, forks, U-turns, and straight lanes.
* **Full Roundabout Exit Sequence:** Verified on both test benches and physical motorcycles from the 1st through the 9th exit (`0x0B` to `0x13`).
* **Real-Time Distance & ETA:** Accurate display in meters or kilometers on the secondary Pod screen.

### 🏎️ Industrial Automotive Instrumentation & Design System
Mobile-first interface designed around telemetry precision, mechanical robustness, and professional dark finish:
* **Dark Technical Palette:** Graphite backgrounds (`#0A0E17`, `#141C28`), high-contrast text (`#E8EDF3`), Cyan accents for Bluetooth/Navigation (`#00C2D9`), Green for normal status/speed (`#22C785`), Amber for warnings (`#F5A623`), and Red for errors (`#E05252`).
* **4 Core Navigation Tabs:**
  * **1. Dashboard:** Bluetooth connection status, live speed, trip summary, and a circular gauge mirroring the physical Tripper display.
  * **2. Navigation:** Active route monitor displaying Google Maps / Waze branded banner, next turn instruction, and remaining distance.
  * **3. Cruise:** Dedicated cruising cockpit featuring an oversized digital speedometer, heading compass, trip distance, and travel duration.
  * **4. Device:** BLE connection controls, auto-reconnect toggle, smart PIN validation banner on initial pairing, **"Forget Device"** button, icon fuzzer, and live telemetry log console.

### 🛡️ Dual-Role GATT Server BLE Engine
* The NXP firmware inside the Tripper Pod requires the phone to act not only as a GATT client, but also to host a local GATT Server with the service UUID `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`.
* OpenTripper handles both roles simultaneously, with a strict 50ms serialization queue, `CRC-16/CCITT-FALSE` packet checksums, and continuous 1.5s keepalive cycles to prevent hardware watchdog timeouts.

### 🔍 Built-in Icon Fuzzer & Diagnostics
* Integrated explorer tool allowing developers and riders to cycle through all 256 opcodes (`0x00` to `0xFF`) directly on their motorcycle, instantly logging observations to `opentripper_logs.txt`.

---

## 📱 Installation & Setup

### Requirements
* Smartphone running **Android 8.0 (Oreo / API 26)** or higher.
* Royal Enfield Hunter 350 with Tripper Pod (or any Royal Enfield equipped with an NXP `RE_DISP` BLE module).

### Step-by-Step Guide

1. **Download the APK:** Get [opentripper.apk](https://github.com/OSolerHugo/opentripper/releases/latest/download/opentripper.apk).
2. **Grant Permissions:**
   * **Bluetooth & Location:** Required to scan for BLE hardware and compute GPS speed telemetry.
   * **Notification Access:** Navigate to *Settings ➔ Notifications ➔ Notification Access* (or *Device & App Notifications*) and enable **OpenTripper** so it can read Google Maps and Waze turn updates.
3. **First-Time Bike Pairing:**
   * Turn the motorcycle ignition key on (the Tripper Pod advertising window remains open for ~30 seconds).
   * Open OpenTripper, navigate to the **Dispositivo (Device)** tab, and tap **CONECTAR TRIPPER**.
   * When the 6-digit PIN code appears on the Tripper display, OpenTripper will automatically display the PIN validation card. Enter the code and tap confirm.
   * Pairing complete! The device is saved and will reconnect automatically on future rides.

---

## 🔬 Protocol & Technical Documentation

For engineers, researchers, and open-source contributors exploring the hardware:

* 📄 **[PROTOCOL.md](PROTOCOL.md):** Complete technical specification including GATT UUIDs, 20-byte packet structure, CRC-16 algorithm, firmware watchdog timings, system command dictionary, and comprehensive icon opcodes.
* 📄 **[PROTOCOLO.md (Português)](PROTOCOLO.md):** Portuguese reference document.
* 📄 **[Architecture & Design Specifications](docs/superpowers/):** Engineering design documents for the notification listener and hybrid navigation pipeline.

---

## 🛠️ Building from Source

OpenTripper is built in native Java using the standard Android SDK:

```bash
# Clone the repository
git clone https://github.com/OSolerHugo/opentripper.git
cd opentripper

# Build debug APK
./gradlew assembleDebug

# Build signed release APK
./gradlew assembleRelease
```

The compiled APK will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## 🤝 Contributing

Contributions from the open-source community are very welcome!
* Discovered a new icon or command opcode on the Tripper display? Submit a Pull Request updating `PROTOCOL.md` or open an Issue.
* New telemetry screens, integrations with additional Bluetooth sensors (TPMS, OBD2 speedometers), or UI enhancements are encouraged.

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

---

## 📄 License

Distributed under the **MIT License**. See [LICENSE](LICENSE) for details.

*Disclaimer: OpenTripper is an independent open-source project and is not affiliated with, endorsed by, or associated with Royal Enfield or NXP Semiconductors.*
