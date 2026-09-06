# Paging Dr. Howard 📟🔐

An open, high-reliability emergency paging system designed for family members to reach each other in urgent situations. Pages override **Do Not Disturb (DND)**, **Silent**, and **Vibrate** modes to ring at full volume with an interactive full-screen alert.

Includes both the native Android client and an interactive browser-based phone simulator that shares the exact same core engine.

---

## 🌟 Key Features

1. **DND & Silent Mode Override**:
   - Uses Android's `AudioAttributes.USAGE_ALARM` with maximum alarm stream volume.
   - Launches a high-priority `USE_FULL_SCREEN_INTENT` activity with screen-wake (`turnScreenOn`, `showWhenLocked`) that wakes locked phones.

2. **Multi-Level Urgency Paging**:
   - **Hey Look! 👀**: A single, friendly chime and amber alert for quick check-ins or non-emergency alerts.
   - **SOS Emergency 🚨**: A continuous, high-volume looping siren with a red alert screen that demands immediate response.

3. **Silent Delivery & Quiet Acknowledgment**:
   - Inbound pages are immediately purged from the transport layer upon receipt.
   - When a recipient taps **"ACKNOWLEDGE & DISMISS"**, a quiet `PAGE_ACK` packet is returned.
   - The sender's phone receives the receipt silently without noisy chimes or banners, displaying a green **"✔ Page Acknowledged"** badge on the contact card and automatically dismissing any active sirens.

4. **End-to-End Cryptographic Security**:
   - Cryptographic key pairs generated locally on-device.
   - Elliptic Curve (`secp256r1`) **ECDH** shared secret derivation for AES-256 encrypted payloads.
   - **ECDSA** digital signatures prevent spoofing; only paired contacts can trigger an alert.
   - Replay protection with timestamps and unique message signatures.

5. **Direct Push Transport (Firebase Realtime Database)**:
   - High-throughput SSE streaming and REST messaging without third-party push notification quota limits.
   - Works on standard Android, de-Googled ROMs, and Amazon Fire OS devices over Wi-Fi and 4G/5G cellular data.

6. **Interactive Two-Phone Web Simulator**:
   - Located in `shared-messaging/src/jsMain/resources/simulator.html`.
   - Runs two simulated devices side-by-side (or against real Android phones).
   - Generates scannable pairing QR codes and tests full bidirectional paging and acks in real-time.

7. **In-App GitHub Auto-Updater**:
   - Automatically checks GitHub Releases on launch.
   - 1-tap seamless update downloads & installs the latest APK build directly from GitHub without needing the Google Play Store.

---

## 📱 Quick Setup

### On Android
1. **Install APK**: Download the latest release from [GitHub Releases](https://github.com/plbogen2/paging-dr-howard/releases/latest).
2. **Grant DND Access**: On first launch, tap **"Grant DND Access in Settings"** so alarms can override Do Not Disturb.
3. **Pair Devices**:
   - Go to the **Setup** tab to view your pairing QR code or tap **"Copy Code"**.
   - On the other phone (or Web Simulator), scan the QR code with the camera or paste the pairing code.
   - Both devices automatically perform a mutual handshake and appear in each other's address book.
4. **Send a Page**: Tap **[Hey Look!]** or **[SOS]** next to the contact's name.

### On the Web Simulator
1. Start the local static file server:
   ```bash
   python -m http.server 8000 --directory shared-messaging/src/jsMain/resources
   ```
2. Open `http://localhost:8000/simulator.html` in your browser.
3. Use the simulator to page between simulated devices, or click **"Show QR Code"** and scan it with your physical Android phone.

---

## 🔒 Privacy & Architecture

- **Zero Cloud Storage**: Contacts, encryption keys, and preferences are stored exclusively on your device.
- **Transport Privacy**: Firebase RTDB only acts as a temporary mailbox for encrypted payloads; pages are dropped immediately upon receipt and plaintext is never stored on the server.
- **Non-Intrusive Background Service**: The persistent listener runs in low-power minimized mode (`IMPORTANCE_MIN` without launcher icon badges or chimes), maintaining instant reachability while staying out of your way.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17
- Android SDK (compileSdk 34, minSdk 26)

### Build Android APK
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```
The resulting APK is generated in `app/build/outputs/apk/`.

### Run Unit Tests
```bash
./gradlew test
```
