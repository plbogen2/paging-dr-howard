# Paging Dr. Howard 📟🔐

An Android emergency paging system designed exclusively for family members to page each other in urgent situations. The page rings at full volume even when the recipient's phone is in **Do Not Disturb (DND)**, **Silent**, or **Vibrate** mode.

---

## 🛠️ How It Works

1. **App-to-App Cryptographic Security**: Uses end-to-end HMAC-SHA256 signature verification and AES payload encryption via a shared **Family Security Key**. Any unauthorized or unauthenticated request is rejected automatically.
2. **High-Priority Push Notification**: Uses Firebase Cloud Messaging (`priority: high`) to wake up the recipient device even in deep Android Doze mode.
3. **Alarm Stream Audio (`USAGE_ALARM`)**: Plays the alarm sound through `AudioAttributes.USAGE_ALARM`. Android routes alarm streams independently from notification/ringer streams, forcing sound through even if ringer volume is 0 or set to silent.
4. **DND Access Override**: Uses `ACCESS_NOTIFICATION_POLICY` permission to ensure the notification channel bypasses Do Not Disturb restrictions.
5. **Lock Screen Emergency Activity**: Uses Android's `USE_FULL_SCREEN_INTENT` to turn on the screen and present a full-screen emergency alert UI directly over the lock screen until dismissed.

---

## 📱 Quick Setup Guide

### 1. Install on Both Phones
Install the app on both Android devices:
- **Parent Phone** (Sender)
- **Daughter Phone** (Recipient)

### 2. Configure Family Security Key
1. On both phones, open **Paging Dr. Howard**.
2. Under **"My Device Setup"**, enter the same **Family Security Key** (*e.g., `MySecretFamilyKey123`*).
3. Tap **"Save Security Key"**.

### 3. Grant DND Access (Recipient's Phone)
1. Tap **"Grant DND Access in Settings"**.
2. Toggle **Paging Dr. Howard** to **Allowed**.
3. Copy your **Device Token** and share it with the Parent phone.

### 4. Sending a Secure Page (Parent's Phone)
1. Open the app and switch to the **"Send Page"** tab.
2. Paste the **Recipient FCM Token**.
3. Enter your name (*e.g., Dad*) and message (*e.g., "Please call home ASAP!"*).
4. Tap **"SEND SECURE EMERGENCY PAGE"**.

---

## 🔒 Privacy & Security
- **App-to-App Direct Verification**: Unsigned or unauthenticated web/cURL requests are rejected silently by the recipient device.
- **Permissions**: All permissions (`ACCESS_NOTIFICATION_POLICY`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`) are declared in `AndroidManifest.xml`.
- **Zero Surveillance**: No continuous location tracking or background surveillance—only triggers when an authenticated emergency page payload is received.
