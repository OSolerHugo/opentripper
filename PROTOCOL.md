# Technical Reference: Royal Enfield Tripper Pod Protocol (Hunter 350)

> 🌐 *Portuguese reference manual available at [PROTOCOLO.md](PROTOCOLO.md).*

Technical reference manual documenting reverse-engineered hardware behaviors, protocol specifications, and Bluetooth Low Energy (BLE) packet structures validated on the **Royal Enfield Hunter 350**.

---

## 1. Hardware Identification & BLE Network

* **Chipset Manufacturer:** NXP Semiconductors
* **MAC Address OUI Prefix:** `00:60:37` (Example validated on Hunter 350: `00:60:37:D2:35:85`)
* **Advertised BLE Device Name:** `RE_DISP`
* **PHY / Layer:** Pure BLE (Bluetooth Low Energy; Classic Bluetooth / BR-EDR not supported).
* **Advertising Window:**
  * The Tripper Pod only broadcasts advertising packets for **less than 30 seconds** after turning on the motorcycle ignition.
  * If no device connects within this window, the BLE radio powers down to preserve the motorcycle battery. The ignition key must be cycled off and on again to restart the window.
  * When the connection is dropped by the Pod itself (e.g. firmware watchdog timeout), advertising resumes immediately.

---

## 2. GATT Services & Characteristics (NXP Wireless UART)

* **Service UUID:** `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0`
* **Characteristic UUID:** `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0`
* **Characteristic Properties:** `WRITE NO RESPONSE`
  * Standard `NOTIFY` is **not supported** on this characteristic.
* **Mandatory Reverse Channel (Pod → Smartphone): Dual-Role GATT**
  * The Tripper Pod does not send data back via standard BLE notifications (`NOTIFY`).
  * Instead, the Pod implements a *dual-role* architecture: in addition to acting as a GATT Server to which the phone connects, it actively behaves as a *GATT Client*, searching for an identical service (`01FF0100...`) hosted locally on the smartphone to write return packets.
  * **Root Cause of Web Bluetooth Failure (Google Chrome / Web BLE):** The browser Web Bluetooth API only implements the GATT Client role and cannot instantiate a local `GATT Server`. Because the Pod cannot find the reverse channel on the smartphone, its internal firmware closes the connection after ~1 to 2 seconds.
  * **Solution in Native Android APK:** OpenTripper calls `BluetoothManager.openGattServer()` to instantiate the local NXP service with write permissions before connecting to the Pod, ensuring 100% stable communication.

---

## 3. Packet Format & Data Integrity

* **Fixed Packet Length:** Exactly **20 bytes** (18 bytes payload + 2 bytes CRC).
* **Integrity Algorithm:** `CRC-16/CCITT-FALSE`
  * **Polynomial:** `0x1021`
  * **Initial Value:** `0xFFFF`
  * **Byte Order:** MSB-first (Big-Endian in bytes `[18..19]`)
  * **Final XOR:** None (`0x0000`)
  * **Calculation Scope:** Computed strictly across the first 18 bytes (`[0..17]`).

---

## 4. Timings, Watchdogs & Anti-Drop Rules

### A. Post-Connection Stabilization Delay (Crucial)
* **Rule:** Wait **200 ms to 300 ms** immediately after physical connection and GATT service discovery before sending the very first byte.
* **Reason:** The NXP microcontroller requires this delay to settle its BLE stack. Immediate packet transmission results in instantaneous disconnection by firmware.

### B. Write Serialization
* **Rule:** Never send concurrent packets. Maintain a strict FIFO queue with a **50 ms to 60 ms** delay between consecutive writes.

### C. General Firmware Watchdog (Keepalive)
* **Rule:** The Tripper Pod terminates the connection (`status=19`) if it receives no valid data for approximately **5 seconds**.
* **Solution:** Transmit a heartbeat packet (`PING_FW`) every **1.5 to 2.0 seconds**.

### D. Screen & Maneuver Watchdog (Critical)
* **Rule:** When an active navigation screen (`0x10 0x11`) or compass screen is displayed, the generic `PING_FW` packet is **not** sufficient to renew the display watchdog.
* **Behavior:** The Pod interprets the lack of active maneuver refresh as a frozen GPS app and terminates the link after ~4 to 5 seconds.
* **Solution:** The keepalive loop must **retransmit the active navigation packet itself** every 1.5 seconds.

---

## 5. Command Packet Dictionary (Hexadecimal)

### 5.1 Fixed System Packets
| Function | Complete 20-Byte Hex (Payload + CRC) |
|---|---|
| **Initial Handshake (Request PIN)** | `21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7` |
| **Reconnect Handshake (Already Paired)** | `21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45` |
| **Generic Keepalive (`PING_FW`)** | `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9` |
| **Incoming Phone Call Overlay** | `40 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00` + CRC |

### 5.2 PIN Confirmation (`0x20`)
* `[0]`: `0x20`
* `[1..6]`: 6-digit PIN in ASCII characters (e.g. `"123456"` = `0x31 0x32 0x33 0x34 0x35 0x36`)
* `[7..17]`: `0x00`
* `[18..19]`: CRC-16

### 5.3 Clock Synchronization (`0x50`)
* `[0]`: `0x50`
* `[1]`: Hours (24h format: `0` to `23`)
* `[2]`: Minutes (`0` to `59`)
* `[3..17]`: `0x00`
* `[18..19]`: CRC-16

### 5.4 Navigation Maneuver Packet (`CMD_NAVIGATE` - `0x10 0x11`)
```text
Byte [0]    : 0x10
Byte [1]    : 0x11
Byte [2]    : Primary icon opcode (large center display)
Byte [3..4] : Distance value (uint16 big-endian)
Byte [5]    : Unit flag (0x01 = meters up to 999m; 0x02 = tenths of km [km * 10] for >= 1.0 km)
Byte [6]    : Theme / intensity (0x00 = day mode, 0x01 = night mode)
Byte [7]    : Secondary maneuver opcode (small arrow; 0xFF = none)
Byte [8..9] : Secondary / speed-camera distance (0xFF 0xFF = none)
Byte [10]   : 0x41 (fixed)
Byte [11..13]: Bottom line (remaining total distance / ETA; 0xFF 0xFF 0xFF = blank)
Byte [14..17]: 0x00 (padding)
Byte [18..19]: CRC-16
```

### 5.5 Icon Opcode Catalog (Byte `[2]`)

| Hex Code | Mapped Meaning | Status |
|---|---|---|
| `0x00` | Blank screen / Clear icon | Documented |
| `0x01` | Destination reached / Checkered flag | Documented |
| `0x02` | Route start / Departure point | Documented |
| `0x03..0x08` | Departure variations / Preliminary maneuvers | Untested |
| `0x09` | Straight ahead (Arrow up) | **Confirmed on Hunter 350** |
| `0x0A` | Straight ahead (Lane variation) | Documented |
| `0x0B` | Roundabout - 1st Exit | **Confirmed on Hunter 350** |
| `0x0C` | Roundabout - 2nd Exit | **Confirmed on Hunter 350** |
| `0x0D` | Roundabout - 3rd Exit | **Confirmed on Hunter 350** |
| `0x0E` | Roundabout - 4th Exit | **Confirmed on Hunter 350** |
| `0x0F` | Roundabout - 5th Exit | **Confirmed on Hunter 350** |
| `0x10` | Roundabout - 6th Exit | **Confirmed on Hunter 350** |
| `0x11` | Roundabout - 7th Exit | **Confirmed on Hunter 350** |
| `0x12` | Roundabout - 8th Exit | **Confirmed on Hunter 350** |
| `0x13` | Roundabout - 9th Exit | **Confirmed on Hunter 350** |
| `0x14` | Turn Left (90°) | **Confirmed on Hunter 350** |
| `0x15` | Turn Right (90°) | **Confirmed on Hunter 350** |
| `0x16` | Sharp Left | Documented |
| `0x17` | Sharp Right | Documented |
| `0x18` | Slight Left / Keep Left | Documented |
| `0x19` | Slight Right / Keep Right | Documented |
| `0x1A` | Highway exit left | Documented |
| `0x1B` | Highway exit right | Documented |
| `0x1C` | Fork left | Documented |
| `0x1D` | Fork right | Documented |
| `0x1E` | Keep center lane | Documented |
| `0x1F..0x22` | Unmapped range | 🔍 **Fuzzing Target** |
| `0x23` | Crossroad / Pedestrian alert | Suspected |
| `0x24..0x27` | Unmapped range | 🔍 **Fuzzing Target** |
| `0x28` | Speed camera / Radar alert | Suspected |
| `0x29..0x31` | Unmapped range | 🔍 **Fuzzing Target** |
| `0x32` | Roundabout - 1st Exit (Legacy/Alternative) | **Confirmed on Hunter 350** |
| `0x33` | Roundabout - 2nd Exit | Documented |
| `0x34` | Roundabout - 3rd Exit | Documented |
| `0x35` | Roundabout - 4th Exit | Documented |
| `0x36` | Roundabout - 5th Exit | Documented |
| `0x37` | Roundabout - 6th Exit | Documented |
| `0x38` | Roundabout - 7th Exit | Documented |
| `0x39..0x3C` | Roundabout variations | Documented |
| `0x3D` | U-Turn | Documented |
| `0x3E` | Reverse U-Turn | Documented |
| `0x3F` | Unmapped | 🔍 **Fuzzing Target** |
| `0x40` | Road hazard alert | Suspected |
| `0x41` | Fullscreen Compass mode (heading in byte `[14]`) | Documented |
| `0x42` | Lost mobile connection alert (No GPS / No Data) | Documented |
| `0x43` | Speed limit exceeded alert | Suspected |
| `0x44` | Low smartphone battery alert | Documented |
| `0x45` | Incoming call overlay | Documented |
| `0x46..0xFF` | Extended NXP ROM table | 🔍 **Open Fuzzing Range** |

---

## 6. Hardware Capabilities & Physical Boundaries

Understanding what the Tripper Pod can and cannot do:

### Supported Capabilities (Full Software Control):
1. **Rendering Pre-Compiled Vector Icons:** Any of the 256 opcodes (`0x00` to `0xFF`) can be invoked and rendered on the display.
2. **Dynamic Numeric and Status Fields:**
   * Primary distance (meters or tenths of km).
   * Secondary distance / speed radar alerts.
   * Bottom status line (ETA or total trip distance remaining).
3. **Brightness & Contrast:** Day mode (light background) and Night mode (dark background).
4. **Clock Synchronization:** Direct setting of hours and minutes (`0x50`).
5. **Special Modes:** Digital compass (`0x41`) and system alert banners.

### Physical Firmware Limitations:
1. **No Arbitrary Bitmap/Image Streaming:** The Tripper Pod display is driven by an NXP microcontroller with a static flash lookup table of vector glyphs. The 20-byte BLE protocol lacks bandwidth and firmware commands for raw framebuffer streaming.
2. **Fixed Typography:** Font styles, glyph weights, and numeric styles are embedded in firmware.
3. **Firmware Over-the-Air (FOTA):** Modifying the internal MCU firmware over BLE is unsafe without official signed cryptographic keys from Royal Enfield / NXP, carrying severe risk of bricking.

---

## 7. Interactive Fuzzing & Exploration Methodology

OpenTripper includes a built-in **Icon Explorer (Fuzzer)**:
1. Once connected to the motorcycle, the UI can sequentially inject any opcode from `0x00` through `0xFF`.
2. Each opcode is bundled into a valid navigation packet and sustained by the 1.5s keepalive loop, allowing the rider to comfortably examine the display.
3. Quick jump buttons skip directly to unexplored opcode blocks.
4. The **"📝 Log Observation"** button immediately writes findings to the persistent `opentripper_logs.txt` file.

---

## 8. Turn-by-Turn Maneuver Mapping (Google Maps & Waze)

OpenTripper converts notification strings from Waze and Google Maps into Tripper Pod opcodes via its heuristic engine (`TurnByTurnService`):

| Pattern Detected in Notification Text | Hex Opcode | Next Maneuver [7] | Displayed Icon |
|---|---|---|---|
| `"roundabout"` / `"rotatória"` + `"1st"` / `"1ª"` / `"exit 1"` | `0x32` | `0x32` | Roundabout - 1st Exit (Counter-Clockwise / Brazil) |
| `"roundabout"` / `"rotatória"` + `"2nd"` / `"2ª"` / `"exit 2"` | `0x33` | `0x33` | Roundabout - 2nd Exit |
| `"roundabout"` / `"rotatória"` + `"3rd"` / `"3ª"` / `"exit 3"` | `0x34` | `0x34` | Roundabout - 3rd Exit |
| `"roundabout"` / `"rotatória"` + `"4th"` / `"4ª"` / `"exit 4"` | `0x35` | `0x35` | Roundabout - 4th Exit |
| `"roundabout"` / `"rotatória"` + `"5th"` / `"5ª"` / `"exit 5"` | `0x36` | `0x36` | Roundabout - 5th Exit |
| `"roundabout"` / `"rotatória"` + `"6th"` / `"6ª"` / `"exit 6"` | `0x37` | `0x37` | Roundabout - 6th Exit |
| `"roundabout"` / `"rotatória"` + `"7th"` / `"7ª"` / `"exit 7"` | `0x38` | `0x38` | Roundabout - 7th Exit |
| `"roundabout"` / `"rotatória"` + `"8th"` / `"8ª"` / `"exit 8"` | `0x39` | `0x39` | Roundabout - 8th Exit |
| `"roundabout"` / `"rotatória"` + `"9th"` / `"9ª"` / `"exit 9"` | `0x3A` | `0x3A` | Roundabout - 9th Exit |
| `"roundabout"` generic base | `0x31` | `0x31` | Roundabout Base (Counter-Clockwise) |
| `"u-turn"`, `"retorno"` | `0x3D` | `0x3D` | U-Turn (Counter-Clockwise) |
| `"sharp left"`, `"curva acentuada à esquerda"` | `0x16` | `0x16` | Sharp Left |
| `"sharp right"`, `"curva acentuada à direita"` | `0x17` | `0x17` | Sharp Right |
| `"slight left"`, `"curva suave à esquerda"` | `0x18` | `0x18` | Slight Left |
| `"slight right"`, `"curva suave à direita"` | `0x19` | `0x19` | Slight Right |
| `"keep left"`, `"mantenha à esquerda"` | `0x28` | `0x2B` | Keep Left |
| `"keep right"`, `"mantenha à direita"` | `0x27` | `0x2C` | Keep Right |
| `"exit left"`, `"saída à esquerda"`, ramp | `0x2E` | `0x2B` | Highway Exit Left |
| `"exit right"`, `"saída à direita"`, ramp | `0x2D` | `0x2C` | Highway Exit Right |
| `"turn left"`, `"left"`, `"vire à esquerda"` | `0x14` | `0x14` | Turn Left (90°) |
| `"turn right"`, `"right"`, `"vire à direita"` | `0x15` | `0x15` | Turn Right (90°) |
| `"fork left"`, `"bifurcação à esquerda"` | `0x06` | `0x06` | Fork Left |
| `"fork right"`, `"bifurcação à direita"` | `0x05` | `0x05` | Fork Right |
| `"ferry"`, `"balsa"` | `0x3E` | `0x3E` | Ferryboat |
| `"destination"`, `"arrived"`, `"chegou"` | `0x00` | `0xFF` | Destination Reached (Checkered Flag) |
| `"re-routing"`, `"recalculando"` | `0x1C` | `0xFF` | Re-routing (Loading Icon) |
| `"radar"`, `"speed camera"` | — | `0x3C` | Speed Camera Alert (Small Arrow [7]) |
| `"continue"`, `"head straight"`, `"siga em frente"` | `0x09` | `0x09` | Straight Ahead (Cruise) |

### Distance Encoding (`Bytes [3..5]`):
* **Values in Meters (up to 999m):** `Bytes [3..4] = uint16 value`, `Byte [5] = 0x01`. (e.g. `250 m` ➔ `0x00 0xFA 0x01`).
* **Values in Kilometers (>= 1.0 km):** Converted to tenths of a kilometer (`km * 10`). `Bytes [3..4] = uint16 value`, `Byte [5] = 0x02`. (e.g. `3.5 km` ➔ `35` tenths ➔ `0x00 0x23 0x02`).

### Dynamic Proximity LED Ring (`Byte [6]`):
The outer circular LED ring lights up in steps as the motorcycle approaches the maneuver:
* **Distance > 95 m:** `0x00` (Ring unlit)
* **Distance ≤ 95 m:** `0x10`
* **Distance ≤ 70 m:** `0x20`
* **Distance ≤ 45 m:** `0x30`
* **Distance ≤ 20 m:** `0x40`
* **Distance ≤ 10 m:** `0x50` (Ring fully lit)

### Speedometer Emulation (Cruise Mode):
Since the Tripper Pod firmware does not feature a native speedometer screen, OpenTripper emulates a digital speedometer by displaying current speed in `km/h` using the metric distance field (`Byte [3..4]`) with unit `m` (`Byte [5] = 0x01`) and the straight arrow (`0x09`). The bottom line (`Bytes [11..13]`) displays trip distance in kilometers.
