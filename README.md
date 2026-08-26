# Paging Dr. Howard 📟

An Android emergency paging system designed for family members to page each other in urgent situations. The page rings at full volume even when the recipient's phone is in **Do Not Disturb (DND)**, **Silent**, or **Vibrate** mode.

---

## 🛠️ How It Works

1. **High-Priority Push Notification**: Uses Firebase Cloud Messaging (`priority: high`) to wake up the recipient device even in deep Android Doze mode.
2. **Alarm Stream Audio (`USAGE_ALARM`)**: Plays the alarm sound through `AudioAttributes.USAGE_ALARM`. Android routes alarm streams independently from notification/ringer streams, forcing sound through even if ringer volume is 0 or set to silent.
3. **DND Access Override**: Uses `ACCESS_NOTIFICATION_POLICY` permission to ensure the notification channel bypasses Do Not Disturb restrictions.
4. **Lock Screen Emergency Activity**: Uses Android's `USE_FULL_SCREEN_INTENT` to turn on the screen and present a full-screen emergency alert UI directly over the lock screen until dismissed.

---

## 📱 Quick Setup Guide

### 1. Build & Install on Both Phones
Open this project in **Android Studio**, connect the Android devices, and install the app on both:
- **Parent Phone** (Sender)
- **Daughter Phone** (Recipient)

### 2. Recipient Setup (Daughter's Phone)
1. Open **Paging Dr. Howard**.
2. Tap **"Grant DND Access in Settings"**.
3. In the Android settings screen that opens, toggle **Paging Dr. Howard** to **Allowed**.
4. Go back to the app and copy your **Device Token**.
5. Send/paste that token to the Parent phone.

### 3. Sending a Page (Parent's Phone)
1. Open the app and switch to the **"Send Page"** tab.
2. Paste your daughter's **Device Token**.
3. Enter your name (*e.g., Dad*) and an urgent message (*e.g., "Please call home ASAP!"*).
4. Tap **"SEND EMERGENCY PAGE NOW"**.

---

## 🌐 Sending Pages via Web / cURL (Optional)

You can also send an emergency page directly from any browser or terminal using cURL:

```bash
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FIREBASE_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "RECIPIENT_DEVICE_TOKEN",
    "priority": "high",
    "data": {
      "sender": "Dad",
      "message": "Call me ASAP!"
    }
  }'
```

---

## 🔒 Privacy & Permissions
- All permissions (`ACCESS_NOTIFICATION_POLICY`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`) are declared in `AndroidManifest.xml`.
- No continuous location tracking or background surveillance—only triggers when an explicit emergency page payload is received.
