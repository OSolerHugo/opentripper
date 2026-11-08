# Contributing to OpenTripper

Thank you for your interest in contributing to **OpenTripper**! We welcome contributions from riders, reverse engineers, Android developers, and motorcycle enthusiasts worldwide.

---

## 🛠️ How You Can Contribute

1. **Protocol Reverse Engineering:**
   - Test unmapped opcodes using the built-in Fuzzer on your Royal Enfield Hunter 350, Meteor, or Classic.
   - Record findings in `opentripper_logs.txt` and submit an update to [PROTOCOL.md](PROTOCOL.md).
2. **Turn-by-Turn Parsing:**
   - Add support for new navigation languages or localized turn instructions in `TurnParser.java`.
3. **Cockpit & Telemetry Features:**
   - Enhance the digital gauges, speed calculations, or trip recording.
4. **Bug Reports & Feature Requests:**
   - Open an [Issue on GitHub](https://github.com/OSolerHugo/opentripper/issues) with detailed reproduction steps, smartphone model, and Android OS version.

---

## 💻 Development Workflow

### Prerequisites
- Android Studio Ladybug or later (or CLI tools).
- JDK 17.
- Android SDK Platform 34.

### Building
```bash
# Clone the repository
git clone https://github.com/OSolerHugo/opentripper.git
cd opentripper

# Build debug APK
./gradlew assembleDebug
```

### Pull Request Guidelines
1. Fork the repository and create a descriptive branch:
   ```bash
   git checkout -b feature/roundabout-enhancements
   ```
2. Follow existing code formatting conventions (Java standard, clean comments).
3. Do not modify the release keystore or change the signing configuration without discussion.
4. Ensure `./gradlew assembleDebug` passes without errors before submitting.
5. Submit a Pull Request targeting the `main` branch with a clear description of changes.

---

## 📄 License
By contributing to OpenTripper, you agree that your contributions will be licensed under the [MIT License](LICENSE).
