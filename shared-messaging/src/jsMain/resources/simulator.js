// PagerCoreEngine: Shared single-source-of-truth message processing engine
class PagerCoreEngine {
  constructor(myTopicId, myName, relayServerUrl = "https://paging-dr-howard-default-rtdb.firebaseio.com/", startTimeMs = 0) {
    this.myTopicId = myTopicId;
    this.myName = myName;
    this.relayServerUrl = relayServerUrl;
    this.startTimeMs = startTimeMs;
    this.dismissedKeys = new Set();
    this.processedSignatures = new Set();
    this.lastDismissedAlertTimestamp = 0;
  }

  isMessageDismissed(messageKey) {
    return this.dismissedKeys.has(messageKey);
  }

  markMessageDismissed(messageKey, timestamp = 0) {
    if (messageKey) {
      this.dismissedKeys.add(messageKey);
      if (this.dismissedKeys.size > 200) {
        const firstKey = this.dismissedKeys.values().next().value;
        this.dismissedKeys.delete(firstKey);
      }
    }
    if (timestamp > this.lastDismissedAlertTimestamp) {
      this.lastDismissedAlertTimestamp = timestamp;
    }
  }

  processRawEvent(eventString, currentTimestamp = Date.now()) {
    const trimmed = (eventString || "").trim();
    if (!trimmed) return [];

    try {
      const rootElement = JSON.parse(trimmed);
      if (typeof rootElement !== 'object' || rootElement === null) return [];

      // 1. Firebase RTDB streaming format: {"path":"...", "data":...}
      if ('data' in rootElement || 'path' in rootElement) {
        const path = rootElement.path || "";
        const dataElem = rootElement.data;

        // RTDB node deletion event: data is null
        if (dataElem === null || dataElem === undefined) {
          return [];
        }

        if (typeof dataElem === 'object') {
          if ('type' in dataElem) {
            const key = path.replace(/^\//, '').trim() || null;
            return [this.processPayloadObject(dataElem, key, currentTimestamp)];
          } else {
            // Root collection put: {"path":"/", "data":{"key1":{...}, "key2":{...}}}
            const events = [];
            for (const [key, child] of Object.entries(dataElem)) {
              if (child && typeof child === 'object' && 'type' in child) {
                events.push(this.processPayloadObject(child, key, currentTimestamp));
              }
            }
            return events;
          }
        }
        return [];
      } else {
        // Standard single payload
        return [this.processPayloadObject(rootElement, null, currentTimestamp)];
      }
    } catch (e) {
      return [];
    }
  }

  processPayloadObject(obj, messageKey, currentTimestamp = Date.now()) {
    const type = obj.type || "PAGE";
    const senderName = obj.senderName || "Family Member";
    const senderTopicId = obj.senderTopicId || "";
    const senderPublicKey = obj.senderPublicKey || "";
    const level = obj.level || "SOS";
    const timestamp = Number(obj.timestamp) || 0;
    const ciphertext = obj.ciphertext || "";
    const signature = obj.signature || "";

    // Rule 1: Dismissal filter
    if (messageKey && this.isMessageDismissed(messageKey)) {
      return { type: "PURGE_REQUIRED", messageKey };
    }

    // Rule 2: Self-echo filter
    if (senderTopicId && senderTopicId === this.myTopicId) {
      return messageKey ? { type: "PURGE_REQUIRED", messageKey } : { type: "IGNORED" };
    }

    // Rule 3: Replay protection (10 minutes drift)
    if (timestamp > 0 && Math.abs(currentTimestamp - timestamp) > 600000) {
      return messageKey ? { type: "PURGE_REQUIRED", messageKey } : { type: "IGNORED" };
    }

    // Rule 4: Stale history filter (messages prior to session connection)
    if (this.startTimeMs > 0 && timestamp > 0 && timestamp < (this.startTimeMs - 5000)) {
      return messageKey ? { type: "PURGE_REQUIRED", messageKey } : { type: "IGNORED" };
    }

    // Rule 5: Timestamp dismissal cutoff for emergency pages
    if (type === "PAGE" && timestamp > 0 && timestamp <= this.lastDismissedAlertTimestamp) {
      return messageKey ? { type: "PURGE_REQUIRED", messageKey } : { type: "IGNORED" };
    }

    // Rule 6: Signature deduplication
    const dedupeKey = signature || `${senderTopicId}:${timestamp}`;
    if (this.processedSignatures.has(dedupeKey)) {
      return { type: "IGNORED" };
    }
    this.processedSignatures.add(dedupeKey);
    if (this.processedSignatures.size > 200) {
      const firstSig = this.processedSignatures.values().next().value;
      this.processedSignatures.delete(firstSig);
    }

    // Type routing
    if (type === "PAGE") {
      return {
        type: "ALERT_TRIGGERED",
        senderName,
        senderTopicId,
        messageText: ciphertext,
        level,
        timestamp,
        messageKey
      };
    } else if (type === "PAGE_ACK") {
      return {
        type: "PAGE_ACK_RECEIVED",
        senderName,
        senderTopicId,
        messageKey
      };
    } else if (type === "PAIRING_HANDSHAKE") {
      return {
        type: "PAIRING_RECEIVED",
        senderName,
        senderTopicId,
        senderPublicKey,
        requiresReply: true,
        messageKey
      };
    } else if (type === "PAIRING_HANDSHAKE_REPLY") {
      return {
        type: "PAIRING_RECEIVED",
        senderName,
        senderTopicId,
        senderPublicKey,
        requiresReply: false,
        messageKey
      };
    } else if (type === "NAME_UPDATE") {
      return {
        type: "NAME_UPDATE_RECEIVED",
        newName: senderName,
        senderTopicId,
        messageKey
      };
    }

    return { type: "IGNORED" };
  }

  buildPagePayload(targetTopicId, level, messageText, timestamp = Date.now(), senderPublicKey = "", signature = "") {
    return {
      type: "PAGE",
      senderName: this.myName,
      senderTopicId: this.myTopicId,
      senderPublicKey,
      level,
      timestamp,
      ciphertext: messageText,
      signature
    };
  }

  buildAckPayload(timestamp = Date.now(), senderPublicKey = "", signature = "") {
    return {
      type: "PAGE_ACK",
      senderName: this.myName,
      senderTopicId: this.myTopicId,
      senderPublicKey,
      level: "HEY_LOOK",
      timestamp,
      ciphertext: `Alert Acknowledged by ${this.myName}`,
      signature
    };
  }

  buildPairingPayload(isReply = false, timestamp = Date.now(), senderPublicKey = "") {
    return {
      type: isReply ? "PAIRING_HANDSHAKE_REPLY" : "PAIRING_HANDSHAKE",
      senderName: this.myName,
      senderTopicId: this.myTopicId,
      senderPublicKey,
      timestamp
    };
  }
}

// Persistence Helpers
    const STORAGE_KEY = "pdh_simulator_state_v1";

    function loadSavedState() {
      try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) return JSON.parse(raw);
      } catch (e) {
        console.error("Failed to load simulator state from localStorage", e);
      }
      return null;
    }

    function saveSimState() {
      try {
        const state = {
          topicA,
          topicB,
          phoneA: {
            name: phoneA.name,
            topic: phoneA.topic,
            contacts: phoneA.contacts
          },
          phoneB: {
            name: phoneB.name,
            topic: phoneB.topic,
            contacts: phoneB.contacts
          }
        };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
      } catch (e) {
        console.error("Failed to save simulator state to localStorage", e);
      }
    }

// State
    let relayBase = "https://paging-dr-howard-default-rtdb.firebaseio.com";
    const savedState = loadSavedState();

    let topicA = savedState?.topicA || ('pdh_sim_a_' + Math.random().toString(36).substring(2, 9));
    let topicB = savedState?.topicB || ('pdh_sim_b_' + Math.random().toString(36).substring(2, 9));

    let phoneA = {
      name: savedState?.phoneA?.name || "Dad",
      topic: savedState?.phoneA?.topic || topicA,
      contacts: savedState?.phoneA?.contacts || {},
      eventSource: null,
      activeAlertSenderTopic: null,
      engine: new PagerCoreEngine(savedState?.phoneA?.topic || topicA, savedState?.phoneA?.name || "Dad", relayBase, Date.now())
    };

    let phoneB = {
      name: savedState?.phoneB?.name || "Daughter",
      topic: savedState?.phoneB?.topic || topicB,
      contacts: savedState?.phoneB?.contacts || {},
      eventSource: null,
      activeAlertSenderTopic: null,
      engine: new PagerCoreEngine(savedState?.phoneB?.topic || topicB, savedState?.phoneB?.name || "Daughter", relayBase, Date.now())
    };

    // Save initial state so topics stay persistent across reloads immediately
    saveSimState();

    // Cooldown management
    const cooldowns = {
      phoneA: {},
      phoneB: {}
    };

    const activeAlertIntervals = {};
    let html5QrCodeScanner = null;
    let currentScanningPhoneKey = null;
    let currentQrCodeObj = null;

    function clearLogs() {
      document.getElementById('phoneA_log').innerHTML = '';
      document.getElementById('phoneB_log').innerHTML = '';
    }

    function logDevice(deviceKey, msg, color = "text-emerald-400") {
      const containerId = deviceKey === 'phoneA' ? 'phoneA_log' : 'phoneB_log';
      const logBox = document.getElementById(containerId);
      if (!logBox) return;
      const entry = document.createElement('div');
      entry.className = color;
      const time = new Date().toLocaleTimeString();
      entry.textContent = `[${time}] ${msg}`;
      logBox.appendChild(entry);
      logBox.scrollTop = logBox.scrollHeight;
    }

    function logBoth(msg, color = "text-slate-400") {
      logDevice('phoneA', msg, color);
      logDevice('phoneB', msg, color);
    }

    function startCooldown(deviceKey, topicId, seconds = 10) {
      if (!cooldowns[deviceKey]) cooldowns[deviceKey] = {};
      cooldowns[deviceKey][topicId] = seconds;
      renderContacts(deviceKey, deviceKey === 'phoneA' ? phoneA : phoneB);

      const interval = setInterval(() => {
        if (cooldowns[deviceKey] && cooldowns[deviceKey][topicId] > 1) {
          cooldowns[deviceKey][topicId]--;
          renderContacts(deviceKey, deviceKey === 'phoneA' ? phoneA : phoneB);
        } else {
          clearInterval(interval);
          if (cooldowns[deviceKey]) {
            delete cooldowns[deviceKey][topicId];
          }
          renderContacts(deviceKey, deviceKey === 'phoneA' ? phoneA : phoneB);
        }
      }, 1000);
    }

    function getSimulatorPairingCode(phoneKey) {
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      const pairingPayload = {
        id: "sim_" + phoneKey,
        name: phoneObj.name,
        topicId: phoneObj.topic,
        publicKey: "",
        passphrase: "",
        serverUrl: relayBase
      };
      return "PAGING_PAIR:" + JSON.stringify(pairingPayload);
    }

    function copySimulatorPairingCode(phoneKey) {
      const codeString = getSimulatorPairingCode(phoneKey);
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      navigator.clipboard.writeText(codeString).then(() => {
        alert(`Copied Pairing Code for ${phoneObj.name}!\n\nYou can now paste this directly into your Android phone under "Scan / Pair Family Member".`);
        logDevice(phoneKey, `📋 Generated & copied pairing code: ${codeString}`, 'text-cyan-300 font-mono text-[10px]');
      }).catch(() => {
        prompt("Copy pairing code manually:", codeString);
      });
    }

    // Modal QR Code Generation
    function openQrModal(phoneKey) {
      console.log('openQrModal called for', phoneKey);
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      const title = document.getElementById('qrModalTitle');
      const container = document.getElementById('qrcodeContainer');
      const payloadText = document.getElementById('qrPayloadText');
      const modal = document.getElementById('qrModal');

      const code = getSimulatorPairingCode(phoneKey);
      title.textContent = `${phoneObj.name}'s Device Pairing QR Code`;
      payloadText.textContent = code;

      const render = () => {
        container.innerHTML = '';
        currentQrCodeObj = new QRCode(container, {
          text: code,
          width: 200,
          height: 200,
          colorDark: "#000000",
          colorLight: "#ffffff",
          correctLevel: QRCode.CorrectLevel.M
        });
        modal.classList.remove('hidden');
        logDevice(phoneKey, `📱 Displaying Pairing QR Code for "${phoneObj.name}"`, "text-cyan-300 font-bold");
      };

      if (typeof QRCode === 'undefined') {
        console.warn('QRCode library not loaded, loading dynamically');
        const script = document.createElement('script');
        script.src = 'https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js';
        script.onload = () => {
          console.log('QRCode library loaded dynamically');
          render();
        };
        script.onerror = () => {
          alert('Failed to load QRCode library');
        };
        document.head.appendChild(script);
      } else {
        render();
      }
    }

    function closeQrModal() {
      document.getElementById('qrModal').classList.add('hidden');
    }

    function copyQrPayload() {
      const code = document.getElementById('qrPayloadText').textContent;
      navigator.clipboard.writeText(code).then(() => {
        alert("Copied Pairing Code to clipboard!");
      });
    }

    // QR Code Scanner using html5-qrcode
    async function openScannerModal(phoneKey) {
      currentScanningPhoneKey = phoneKey;
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      document.getElementById('scannerModalTitle').textContent = `${phoneObj.name}: Scan Family QR Code`;
      document.getElementById('scannerModal').classList.remove('hidden');

      try {
        html5QrCodeScanner = new Html5Qrcode("qr-reader");
        await html5QrCodeScanner.start(
          { facingMode: "user" }, // use webcam/front camera
          {
            fps: 10,
            qrbox: { width: 220, height: 220 }
          },
          (decodedText, decodedResult) => {
            handleScannedCode(currentScanningPhoneKey, decodedText);
            closeScannerModal();
          },
          (errorMessage) => {
            // parse error, ignore continuously
          }
        );
      } catch (err) {
        alert("Could not access camera/webcam: " + err);
        closeScannerModal();
      }
    }

    async function closeScannerModal() {
      if (html5QrCodeScanner) {
        try {
          await html5QrCodeScanner.stop();
          html5QrCodeScanner.clear();
        } catch (e) {
          console.log(e);
        }
        html5QrCodeScanner = null;
      }
      document.getElementById('scannerModal').classList.add('hidden');
    }

    function handleScannedCode(phoneKey, rawCode) {
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      logDevice(phoneKey, `📷 [SCANNED QR CODE] "${rawCode}"`, "text-indigo-400 font-bold");
      importPairingCode(phoneKey, rawCode);
    }

    function importPairingCode(phoneKey, rawVal, defaultName = "My Android Phone") {
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      let name = defaultName;
      let parsedTopic = rawVal.trim();

      if (rawVal.startsWith("PAGING_PAIR:")) {
        try {
          const jsonStr = rawVal.substring("PAGING_PAIR:".length);
          const obj = JSON.parse(jsonStr);
          if (obj.topicId) {
            parsedTopic = obj.topicId;
            if (obj.name) name = obj.name;
          }
        } catch (e) {
          console.error("Failed to parse pairing code JSON", e);
        }
      }

      phoneObj.contacts[parsedTopic] = {
        name: name,
        topicId: parsedTopic
      };

      saveSimState();
      renderContacts(phoneKey, phoneObj);
      logDevice(phoneKey, `➕ [PAIRED CONTACT] "${name}" (${parsedTopic})`, "text-emerald-400 font-bold");
      
      // Auto-send pairing handshake
      logDevice(phoneKey, `   Sending PAIRING_HANDSHAKE packet to ${parsedTopic}...`, "text-indigo-300");
      const handshake = {
        type: "PAIRING_HANDSHAKE",
        senderName: phoneObj.name,
        senderTopicId: phoneObj.topic,
        timestamp: Date.now()
      };
      sendPushPayload(phoneKey, parsedTopic, handshake, `Pairing Handshake from ${phoneObj.name}`, "4", "handshake");
      alert(`Successfully paired with "${name}"!`);
    }

    function addExternalContact(phoneKey) {
      const topicInput = document.getElementById(`${phoneKey}_customTopic`);
      const nameInput = document.getElementById(`${phoneKey}_customName`);
      
      let rawVal = topicInput.value.trim();
      let name = nameInput.value.trim() || "My Android Phone";

      if (!rawVal) {
        alert("Please enter a Topic ID (e.g. pdh_...) or Pairing Code!");
        return;
      }

      importPairingCode(phoneKey, rawVal, name);
      topicInput.value = "";
    }

    function initFirebaseListener(phoneKey, phoneObj, statusElId) {
      if (phoneObj.dbRef) {
        phoneObj.dbRef.off();
      }
      const cleanTopic = phoneObj.topic.replace(/[^a-zA-Z0-9_-]/g, '_');
      const channelRef = db.ref(`channels/${cleanTopic}`);
      phoneObj.dbRef = channelRef;

      logDevice(phoneKey, `⚡ Connected Firebase RTDB: channels/${cleanTopic}`, "text-cyan-400 font-bold");
      document.getElementById(statusElId).innerHTML = "🟢 Connected";
      document.getElementById(statusElId).className = "text-emerald-400 font-bold";

      channelRef.limitToLast(10).on('child_added', (snapshot) => {
        const payload = snapshot.val();
        const msgKey = snapshot.key;
        if (!payload) return;

        // Process message through shared PagerCoreEngine
        const event = phoneObj.engine.processPayloadObject(payload, msgKey, Date.now());
        handleEngineEvent(phoneKey, phoneObj, event, payload);
      });
    }
    const initSSE = initFirebaseListener;

    async function handleEngineEvent(recipientKey, recipientObj, event, rawPayload) {
      const cleanTopic = recipientObj.topic.replace(/[^a-zA-Z0-9_-]/g, '_');
      const channelRef = db.ref(`channels/${cleanTopic}`);
      const purgeMsg = (key) => {
        if (key) channelRef.child(key).remove().catch(() => {});
      };

      switch (event.type) {
        case "ALERT_TRIGGERED":
          logDevice(recipientKey, `📥 [INCOMING PAGE (${event.level})]`, "text-yellow-400 font-bold");
          logDevice(recipientKey, `   From: "${event.senderName}" (${event.senderTopicId})`, "text-yellow-200");
          logDevice(recipientKey, `   Message: "${event.messageText}"`, "text-slate-300");
          recipientObj.activeAlertMsgKey = event.messageKey;
          recipientObj.activeAlertTimestamp = event.timestamp;
          triggerAlertUI(recipientKey, recipientObj, event);
          break;

        case "PAGE_ACK_RECEIVED":
          logDevice(recipientKey, `✅ [PAGE ACKNOWLEDGED]`, "text-emerald-400 font-extrabold text-sm");
          logDevice(recipientKey, `   "${event.senderName}" acknowledged and silenced your alarm!`, "text-emerald-300 font-bold");
          purgeMsg(event.messageKey);
          break;

        case "PAIRING_RECEIVED":
          recipientObj.contacts[event.senderTopicId] = {
            name: event.senderName,
            topicId: event.senderTopicId
          };
          saveSimState();
          renderContacts(recipientKey, recipientObj);
          logDevice(recipientKey, `   ✔ Added "${event.senderName}" to address book!`, "text-emerald-400 font-bold");
          purgeMsg(event.messageKey);

          if (event.requiresReply) {
            logDevice(recipientKey, `   🔄 Auto-sending PAIRING_HANDSHAKE_REPLY to complete mutual pair...`, "text-indigo-300");
            const replyPacket = recipientObj.engine.buildPairingPayload(true, Date.now());
            sendPushPayload(recipientKey, event.senderTopicId, replyPacket, `Pairing Handshake from ${recipientObj.name}`, "4", "handshake,white_check_mark");
          }
          break;

        case "NAME_UPDATE_RECEIVED":
          if (recipientObj.contacts[event.senderTopicId]) {
            const old = recipientObj.contacts[event.senderTopicId].name;
            recipientObj.contacts[event.senderTopicId].name = event.newName;
            saveSimState();
            renderContacts(recipientKey, recipientObj);
            logDevice(recipientKey, `   ✔ Updated contact name from "${old}" to "${event.newName}"!`, "text-emerald-400 font-bold");
          } else {
            recipientObj.contacts[event.senderTopicId] = {
              name: event.newName,
              topicId: event.senderTopicId
            };
            saveSimState();
            renderContacts(recipientKey, recipientObj);
            logDevice(recipientKey, `   ✔ Saved new contact "${event.newName}"!`, "text-emerald-400 font-bold");
          }
          purgeMsg(event.messageKey);
          break;

        case "PURGE_REQUIRED":
          purgeMsg(event.messageKey);
          break;

        case "IGNORED":
        default:
          break;
      }
    }

    function triggerAlertUI(phoneKey, phoneObj, event) {
      const card = document.getElementById(`${phoneKey}_alertCard`);
      const badge = document.getElementById(`${phoneKey}_alertBadge`);
      const sender = document.getElementById(`${phoneKey}_alertSender`);
      const msg = document.getElementById(`${phoneKey}_alertMsg`);

      phoneObj.activeAlertSenderTopic = event.senderTopicId;

      card.classList.remove('hidden');
      sender.textContent = `From: ${event.senderName}`;
      msg.textContent = event.messageText || "Alert received!";

      // Clear any prior sound interval
      if (activeAlertIntervals[phoneKey]) {
        clearInterval(activeAlertIntervals[phoneKey]);
      }

      if (event.level === 'SOS') {
        badge.textContent = "🚨 SOS EMERGENCY ALERT";
        badge.className = "font-extrabold text-sm px-2 py-0.5 rounded bg-red-600 text-white";
        card.className = "p-4 rounded-xl border-2 shadow-lg space-y-2 siren-active";
        playSirenSound();
        activeAlertIntervals[phoneKey] = setInterval(() => {
          playSirenSound();
        }, 1500);
      } else {
        badge.textContent = "👀 HEY LOOK! Pager";
        badge.className = "font-extrabold text-sm px-2 py-0.5 rounded bg-amber-500 text-white";
        card.className = "p-4 rounded-xl border-2 border-amber-300 bg-amber-50 shadow-lg space-y-2";
        playChimeSound();
        activeAlertIntervals[phoneKey] = setInterval(() => {
          playChimeSound();
        }, 2000);
      }
    }

    async function dismissAlert(phoneKey) {
      const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
      
      if (activeAlertIntervals[phoneKey]) {
        clearInterval(activeAlertIntervals[phoneKey]);
        delete activeAlertIntervals[phoneKey];
      }

      document.getElementById(`${phoneKey}_alertCard`).classList.add('hidden');
      logDevice(phoneKey, `🔕 Alarm silenced & acknowledged by user.`, "text-slate-300 font-bold");

      // Mark dismissed in PagerCoreEngine and purge active alert page from Firebase RTDB
      if (phoneObj.activeAlertMsgKey) {
        phoneObj.engine.markMessageDismissed(phoneObj.activeAlertMsgKey, phoneObj.activeAlertTimestamp || Date.now());
        const cleanTopic = phoneObj.topic.replace(/[^a-zA-Z0-9_-]/g, '_');
        db.ref(`channels/${cleanTopic}/${phoneObj.activeAlertMsgKey}`).remove().catch(() => {});
        phoneObj.activeAlertMsgKey = null;
      }

      const targetTopic = phoneObj.activeAlertSenderTopic;
      if (targetTopic) {
        logDevice(phoneKey, `📤 Sending PAGE_ACK receipt back to sender topic...`, "text-blue-300");
        const ackPacket = phoneObj.engine.buildAckPayload(Date.now());
        await sendPushPayload(phoneKey, targetTopic, ackPacket, `Page Acknowledged by ${phoneObj.name}`, "4", "white_check_mark");
        phoneObj.activeAlertSenderTopic = null;
      }
    }

    function renderContacts(phoneKey, phoneObj) {
      const list = document.getElementById(`${phoneKey}_contactsList`);
      const count = document.getElementById(`${phoneKey}_contactCount`);
      const contacts = Object.values(phoneObj.contacts);

      count.textContent = `${contacts.length} paired`;

      if (contacts.length === 0) {
        list.innerHTML = `
          <div class="text-center py-6 text-slate-400 bg-white rounded-lg border border-dashed border-slate-300">
            No family paired yet.<br>Tap "Show QR Code" to scan with your phone!
          </div>`;
        return;
      }

      list.innerHTML = '';
      contacts.forEach(c => {
        const cd = (cooldowns[phoneKey] && cooldowns[phoneKey][c.topicId]) || 0;
        const isCoolingDown = cd > 0;

        const item = document.createElement('div');
        item.className = "bg-white p-3 rounded-xl border border-slate-200 shadow-sm space-y-2";
        
        const heyBtnHtml = isCoolingDown
          ? `<button disabled class="bg-slate-300 text-slate-500 font-bold py-1.5 px-2 rounded-lg text-[11px] flex items-center justify-center gap-1 cursor-not-allowed">
               <span>⏳</span> <span>Wait (${cd}s)</span>
             </button>`
          : `<button onclick="sendRemotePage('${phoneKey}', '${c.topicId}', 'HEY_LOOK')" class="bg-amber-500 hover:bg-amber-600 text-white font-bold py-1.5 px-2 rounded-lg text-[11px] flex items-center justify-center gap-1 transition">
               <span>👀</span> <span>Hey Look!</span>
             </button>`;

        const sosBtnHtml = isCoolingDown
          ? `<button disabled class="bg-slate-300 text-slate-500 font-bold py-1.5 px-2 rounded-lg text-[11px] flex items-center justify-center gap-1 cursor-not-allowed">
               <span>⏳</span> <span>Wait (${cd}s)</span>
             </button>`
          : `<button onclick="sendRemotePage('${phoneKey}', '${c.topicId}', 'SOS')" class="bg-red-600 hover:bg-red-700 text-white font-bold py-1.5 px-2 rounded-lg text-[11px] flex items-center justify-center gap-1 transition shadow">
               <span>🚨</span> <span>SOS Page</span>
             </button>`;

        item.innerHTML = `
          <div class="flex justify-between items-center">
            <span class="font-extrabold text-slate-800 text-sm">👤 ${c.name}</span>
            <span class="text-[9px] bg-slate-100 text-slate-500 font-mono px-1.5 py-0.5 rounded truncate max-w-[130px]">${c.topicId}</span>
          </div>
          <div class="grid grid-cols-2 gap-2 pt-1">
            ${heyBtnHtml}
            ${sosBtnHtml}
          </div>
        `;
        list.appendChild(item);
      });
    }

    async function sendPushPayload(senderDeviceKey, targetTopic, payloadObj, title, priority = "3", tags = "") {
      const cleanTopic = targetTopic.trim().replace(/^https?:\/\/[^\/]+\//, '').replace(/[^a-zA-Z0-9_-]/g, '_');
      logDevice(senderDeviceKey, `📡 [OUTBOUND] Firebase RTDB -> channels/${cleanTopic}...`, 'text-blue-400 font-bold');

      try {
        const newMsgRef = db.ref(`channels/${cleanTopic}`).push();
        await newMsgRef.set({
          ...payloadObj,
          title: title,
          priority: priority,
          timestamp: firebase.database.ServerValue.TIMESTAMP
        });
        logDevice(senderDeviceKey, `   ✔ Delivered via Firebase Realtime Database`, 'text-emerald-400 text-[10px]');
        return true;
      } catch (e) {
        logDevice(senderDeviceKey, `   ✖ Firebase Error: ${e.message}`, 'text-rose-400 text-[10px]');
        return false;
      }
    }

    async function sendRemotePage(senderKey, targetTopic, level) {
      const senderObj = senderKey === 'phoneA' ? phoneA : phoneB;
      const isSos = level === 'SOS';

      startCooldown(senderKey, targetTopic, 10);

      logDevice(senderKey, `🚨 Triggering ${level} alert (10s cooldown started)...`, isSos ? "text-red-400 font-bold" : "text-amber-400 font-bold");

      const text = isSos ? "EMERGENCY: Urgent assistance needed!" : "Hey look! Check your phone when free.";
      const payload = senderObj.engine.buildPagePayload(targetTopic, level, text, Date.now());

      const success = await sendPushPayload(
        senderKey,
        targetTopic,
        payload,
        isSos ? `EMERGENCY ALERT from ${senderObj.name}` : `Hey Look from ${senderObj.name}`,
        isSos ? "5" : "4",
        isSos ? "rotating_light,sos" : "eyes,bell"
      );
    }

    async function performPairing(initiatorKey, targetKey) {
      const initiator = initiatorKey === 'phoneA' ? phoneA : phoneB;
      const target = targetKey === 'phoneA' ? phoneA : phoneB;

      logDevice(initiatorKey, `📷 [PAIRING] Scanned QR code of "${target.name}"`, "text-indigo-400 font-bold");

      initiator.contacts[target.topic] = { name: target.name, topicId: target.topic };
      saveSimState();
      renderContacts(initiatorKey, initiator);

      const handshake = initiator.engine.buildPairingPayload(false, Date.now());

      logDevice(initiatorKey, `   Transmitting PAIRING_HANDSHAKE packet...`, "text-indigo-300");
      await sendPushPayload(initiatorKey, target.topic, handshake, `Pairing Handshake from ${initiator.name}`, "4", "handshake");
    }

    async function performMutualPairShortcut() {
      logBoth("⚡ [1-CLICK MUTUAL PAIR] Linking Phone A and Phone B...", "text-indigo-400 font-bold");
      
      phoneA.contacts[phoneB.topic] = { name: phoneB.name, topicId: phoneB.topic };
      phoneB.contacts[phoneA.topic] = { name: phoneA.name, topicId: phoneA.topic };
      saveSimState();
      renderContacts('phoneA', phoneA);
      renderContacts('phoneB', phoneB);

      const handshake = phoneA.engine.buildPairingPayload(true, Date.now());
      await sendPushPayload('phoneA', phoneB.topic, handshake, `Pairing Handshake from ${phoneA.name}`, "4", "handshake,white_check_mark");
    }

    // Audio synthesizer
    const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    function playChimeSound() {
      if (audioCtx.state === 'suspended') audioCtx.resume();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(587.33, audioCtx.currentTime);
      osc.frequency.setValueAtTime(880, audioCtx.currentTime + 0.15);
      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.6);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.6);
    }

    function playSirenSound() {
      if (audioCtx.state === 'suspended') audioCtx.resume();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(800, audioCtx.currentTime);
      osc.frequency.linearRampToValueAtTime(1200, audioCtx.currentTime + 0.2);
      osc.frequency.linearRampToValueAtTime(800, audioCtx.currentTime + 0.4);
      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.5);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.5);
    }

    // Wiring UI
    function initUI() {
      document.getElementById('phoneA_topic').textContent = `Topic: ${phoneA.topic}`;
      document.getElementById('phoneB_topic').textContent = `Topic: ${phoneB.topic}`;
      document.getElementById('phoneA_name').value = phoneA.name;
      document.getElementById('phoneB_name').value = phoneB.name;
      document.getElementById('logHeaderA').textContent = `Phone A (${phoneA.name}) Log`;
      document.getElementById('logHeaderB').textContent = `Phone B (${phoneB.name}) Log`;

      const handleNameChange = (phoneKey, newName) => {
        const phoneObj = phoneKey === 'phoneA' ? phoneA : phoneB;
        const oldName = phoneObj.name;
        phoneObj.name = newName.trim() || (phoneKey === 'phoneA' ? "Dad" : "Daughter");
        phoneObj.engine.myName = phoneObj.name;
        saveSimState();
        const headerId = phoneKey === 'phoneA' ? 'logHeaderA' : 'logHeaderB';
        document.getElementById(headerId).textContent = `Phone ${phoneKey === 'phoneA' ? 'A' : 'B'} (${phoneObj.name}) Log`;
        logDevice(phoneKey, `✏ Renamed from "${oldName}" to "${phoneObj.name}". Broadcasting NAME_UPDATE...`, "text-cyan-300 font-bold");
        Object.keys(phoneObj.contacts).forEach(t => {
          sendPushPayload(phoneKey, t, {
            type: "NAME_UPDATE",
            senderName: phoneObj.name,
            senderTopicId: phoneObj.topic,
            timestamp: Date.now()
          }, `Name Update from ${phoneObj.name}`, "2");
        });
      };

      document.getElementById('phoneA_name').addEventListener('change', (e) => handleNameChange('phoneA', e.target.value));
      document.getElementById('phoneA_name').addEventListener('blur', (e) => handleNameChange('phoneA', e.target.value));

      document.getElementById('phoneB_name').addEventListener('change', (e) => handleNameChange('phoneB', e.target.value));
      document.getElementById('phoneB_name').addEventListener('blur', (e) => handleNameChange('phoneB', e.target.value));

      document.getElementById('phoneA_pairBtn').addEventListener('click', () => performPairing('phoneA', 'phoneB'));
      document.getElementById('phoneB_pairBtn').addEventListener('click', () => performPairing('phoneB', 'phoneA'));
      document.getElementById('oneClickPairBtn').addEventListener('click', () => performMutualPairShortcut());

      // relaySelect listener removed - using Firebase Realtime Database

      document.getElementById('resetAllBtn').addEventListener('click', () => {
        topicA = 'pdh_sim_a_' + Math.random().toString(36).substring(2, 9);
        topicB = 'pdh_sim_b_' + Math.random().toString(36).substring(2, 9);
        phoneA.topic = topicA;
        phoneB.topic = topicB;
        phoneA.engine = new PagerCoreEngine(topicA, phoneA.name, relayBase, Date.now());
        phoneB.engine = new PagerCoreEngine(topicB, phoneB.name, relayBase, Date.now());
        phoneA.contacts = {};
        phoneB.contacts = {};
        saveSimState();
        phoneA.activeAlertSenderTopic = null;
        phoneB.activeAlertSenderTopic = null;
        cooldowns.phoneA = {};
        cooldowns.phoneB = {};
        if (activeAlertIntervals['phoneA']) clearInterval(activeAlertIntervals['phoneA']);
        if (activeAlertIntervals['phoneB']) clearInterval(activeAlertIntervals['phoneB']);
        document.getElementById('phoneA_topic').textContent = `Topic: ${phoneA.topic}`;
        document.getElementById('phoneB_topic').textContent = `Topic: ${phoneB.topic}`;
        document.getElementById('logTopicA').textContent = `topic: ${phoneA.topic}`;
        document.getElementById('logTopicB').textContent = `topic: ${phoneB.topic}`;
        renderContacts('phoneA', phoneA);
        renderContacts('phoneB', phoneB);
        dismissAlert('phoneA');
        dismissAlert('phoneB');
        clearLogs();
        logBoth("🔄 Reset session topics & address books.", "text-white font-bold");
        initSSE('phoneA', phoneA, 'phoneA_status');
        initSSE('phoneB', phoneB, 'phoneB_status');
      });

      document.getElementById('logTopicA').textContent = `topic: ${phoneA.topic}`;
      document.getElementById('logTopicB').textContent = `topic: ${phoneB.topic}`;
      initSSE('phoneA', phoneA, 'phoneA_status');
      initSSE('phoneB', phoneB, 'phoneB_status');
      renderContacts('phoneA', phoneA);
      renderContacts('phoneB', phoneB);
    }

    initUI();
