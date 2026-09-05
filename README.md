# Paging Dr. Howard 📟🔐

An Android emergency paging system designed exclusively for family members to page each other in urgent situations. The page rings at full volume even when the recipient's phone is in **Do Not Disturb (DND)**, **Silent**, or **Vibrate** mode.

---

## 🌟 Key Features

1. **Zero-Server & Zero-Firebase**:
   - No Firebase project setup required.
   - No backend hosting or server maintenance.
   - Works seamlessly on standard Android phones, de-Googled devices, and Amazon Kindle Fire (Fire OS) tablets over both Wi-Fi and 4G/5G cellular data.

2. **In-App GitHub Auto-Updater**:
   - Automatically checks GitHub Releases on launch.
   - 1-tap seamless update downloads & installs the latest APK build directly from GitHub without needing the Google Play Store.

3. **Multi-Level Paging**:
   - **(1) Hey look! 👀**: Gentle single chime with blue-themed alert screen for quick check-ins.
   - **(2) SOS Emergency 🚨**: Continuous, full-volume looping alarm with flashing red alert screen that demands immediate attention.

4. **End-to-End Cryptographic Security**:
   - Uses Elliptic Curve (`secp256r1`) **ECDH** shared secret derivation for AES-256 payload encryption.
   - Uses **ECDSA** digital signatures per pairing. Only authorized, digitally signed messages from paired family members can ring the device.

5. **Bidirectional 1-Step Pairing & Family Address Book**:
   - Scanning or sharing your Pairing Code connects both devices mutually with an automatic cryptographic handshake.
   - 1-tap address book with dedicated paging buttons for each family member.

6. **DND & Silent Mode Override**:
   - Uses `AudioAttributes.USAGE_ALARM` with max alarm volume to ensure emergency pages punch through silent mode.
   - Uses `USE_FULL_SCREEN_INTENT` and wake locks to turn on the screen over the lockscreen.

---

## 📱 Quick Setup (Takes 30 Seconds)

1. **Install APK on Both Devices**:
   - Download the latest APK from [GitHub Releases](https://github.com/plbogen2/paging-dr-howard/releases/latest).
2. **Grant DND Access**:
   - Tap **"Grant DND Access in Settings"** on initial launch so the alarm can bypass Do Not Disturb mode.
3. **Link Devices**:
   - On Phone A: Go to **My Device Setup** ➔ Tap **"Copy Pairing Code"** (or share via message).
   - On Phone B: Paste in **"Pair New Family Phone"** ➔ Tap **"Add & Pair Phone"**.
   - *Both devices are now paired mutually in each other's address book!*
4. **Paging**:
   - Tap **[ Hey Look! ]** or **[ SOS ]** next to your family member's name.

---

## 🔒 Privacy & Architecture
- **Zero Cloud Storage**: Contacts, encryption keys, and messages are stored locally on-device.
- **Relay Privacy**: Public push transport (`ntfy.sh`) only transports random encrypted bytes and never sees plaintext, contacts, or user identities.
