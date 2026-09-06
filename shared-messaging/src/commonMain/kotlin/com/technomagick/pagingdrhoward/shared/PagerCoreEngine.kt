package com.technomagick.pagingdrhoward.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class SharedPageLevel(val code: String, val title: String, val isLoopingSound: Boolean) {
    HEY_LOOK("HEY_LOOK", "Hey look! 👀", true),
    SOS("SOS", "SOS EMERGENCY 🚨", true);

    companion object {
        fun fromCode(code: String?): SharedPageLevel = entries.find { it.code == code } ?: SOS
    }
}

@Serializable
data class PairingPayload(
    val id: String,
    val name: String,
    val topicId: String = "",
    val publicKey: String = "",
    val passphrase: String = "",
    val serverUrl: String = ""
)

sealed class EngineEvent {
    data class AlertTriggered(
        val senderName: String,
        val senderTopicId: String,
        val messageText: String,
        val level: SharedPageLevel,
        val timestamp: Long,
        val messageKey: String?
    ) : EngineEvent()

    data class PageAckReceived(
        val senderName: String,
        val senderTopicId: String,
        val messageKey: String?
    ) : EngineEvent()

    data class PairingReceived(
        val senderName: String,
        val senderTopicId: String,
        val senderPublicKey: String,
        val requiresReply: Boolean,
        val messageKey: String?
    ) : EngineEvent()

    data class NameUpdateReceived(
        val newName: String,
        val senderTopicId: String,
        val messageKey: String?
    ) : EngineEvent()

    data class PurgeRequired(
        val messageKey: String
    ) : EngineEvent()

    object Ignored : EngineEvent()
}

/**
 * Single source of truth for message handling, filtering, deduplication,
 * and dismissal state across both Android and the Web Simulator.
 */
class PagerCoreEngine(
    var myTopicId: String,
    var myName: String,
    var relayServerUrl: String = "https://paging-dr-howard-default-rtdb.firebaseio.com/",
    var startTimeMs: Long = 0L
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dismissedKeys = mutableSetOf<String>()
    private val processedSignatures = mutableSetOf<String>()
    var lastDismissedAlertTimestamp: Long = 0L

    fun isMessageDismissed(messageKey: String): Boolean = dismissedKeys.contains(messageKey)

    fun markMessageDismissed(messageKey: String, timestamp: Long = 0L) {
        if (messageKey.isNotBlank()) {
            dismissedKeys.add(messageKey)
            if (dismissedKeys.size > 200) {
                val toRemove = dismissedKeys.first()
                dismissedKeys.remove(toRemove)
            }
        }
        if (timestamp > lastDismissedAlertTimestamp) {
            lastDismissedAlertTimestamp = timestamp
        }
    }

    /**
     * Ingests a raw SSE or RTDB event string and evaluates protocol rules.
     * Returns a list of [EngineEvent]s for the UI/service layer to execute.
     */
    fun processRawEvent(eventString: String, currentTimestamp: Long): List<EngineEvent> {
        val trimmed = eventString.trim()
        if (trimmed.isBlank()) return emptyList()

        return try {
            val rootElement = json.parseToJsonElement(trimmed)
            if (rootElement !is JsonObject) return emptyList()

            // 1. Firebase RTDB streaming format: {"path":"...", "data":...}
            if (rootElement.containsKey("data") || rootElement.containsKey("path")) {
                val path = rootElement["path"]?.jsonPrimitive?.contentOrNull ?: ""
                val dataElem = rootElement["data"]

                // RTDB node deletion event: data is null
                if (dataElem == null || dataElem is JsonNull) {
                    return emptyList()
                }

                if (dataElem is JsonObject) {
                    if (dataElem.containsKey("type")) {
                        val key = path.removePrefix("/").trim().ifBlank { null }
                        listOf(processPayloadObject(dataElem, key, currentTimestamp))
                    } else {
                        // Root collection put: {"path":"/", "data":{"key1":{...}, "key2":{...}}}
                        val events = mutableListOf<EngineEvent>()
                        for ((key, child) in dataElem) {
                            if (child is JsonObject && child.containsKey("type")) {
                                events.add(processPayloadObject(child, key, currentTimestamp))
                            }
                        }
                        events
                    }
                } else {
                    emptyList()
                }
            } else {
                // Standard single payload
                listOf(processPayloadObject(rootElement, null, currentTimestamp))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Evaluates a single message JSON object against business rules.
     */
    fun processPayloadObject(obj: JsonObject, messageKey: String?, currentTimestamp: Long): EngineEvent {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "PAGE"
        val senderName = obj["senderName"]?.jsonPrimitive?.contentOrNull ?: "Family Member"
        val senderTopicId = obj["senderTopicId"]?.jsonPrimitive?.contentOrNull ?: ""
        val senderPublicKey = obj["senderPublicKey"]?.jsonPrimitive?.contentOrNull ?: ""
        val levelCode = obj["level"]?.jsonPrimitive?.contentOrNull ?: SharedPageLevel.SOS.code
        val timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
        val ciphertext = obj["ciphertext"]?.jsonPrimitive?.contentOrNull ?: ""
        val signature = obj["signature"]?.jsonPrimitive?.contentOrNull ?: ""
        val level = SharedPageLevel.fromCode(levelCode)

        // Rule 1: Dismissal filter
        if (!messageKey.isNullOrBlank() && isMessageDismissed(messageKey)) {
            return EngineEvent.PurgeRequired(messageKey)
        }

        // Rule 2: Self-echo filter
        if (senderTopicId.isNotBlank() && senderTopicId == myTopicId) {
            return if (!messageKey.isNullOrBlank()) EngineEvent.PurgeRequired(messageKey) else EngineEvent.Ignored
        }

        // Rule 3: Replay protection (10 minutes drift)
        if (timestamp > 0 && kotlin.math.abs(currentTimestamp - timestamp) > 600_000L) {
            return if (!messageKey.isNullOrBlank()) EngineEvent.PurgeRequired(messageKey) else EngineEvent.Ignored
        }

        // Rule 4: Stale history filter (messages prior to session connection)
        if (startTimeMs > 0 && timestamp > 0 && timestamp < (startTimeMs - 5000L)) {
            return if (!messageKey.isNullOrBlank()) EngineEvent.PurgeRequired(messageKey) else EngineEvent.Ignored
        }

        // Rule 5: Timestamp dismissal cutoff for emergency pages
        if (type == "PAGE" && timestamp > 0 && timestamp <= lastDismissedAlertTimestamp) {
            return if (!messageKey.isNullOrBlank()) EngineEvent.PurgeRequired(messageKey) else EngineEvent.Ignored
        }

        // Rule 6: Signature deduplication
        val dedupeKey = if (signature.isNotBlank()) signature else "$senderTopicId:$timestamp"
        if (processedSignatures.contains(dedupeKey)) {
            return EngineEvent.Ignored
        }
        processedSignatures.add(dedupeKey)
        if (processedSignatures.size > 200) {
            val toRemove = processedSignatures.first()
            processedSignatures.remove(toRemove)
        }

        // Type routing
        return when (type) {
            "PAGE" -> EngineEvent.AlertTriggered(
                senderName = senderName,
                senderTopicId = senderTopicId,
                messageText = ciphertext,
                level = level,
                timestamp = timestamp,
                messageKey = messageKey
            )
            "PAGE_ACK" -> EngineEvent.PageAckReceived(
                senderName = senderName,
                senderTopicId = senderTopicId,
                messageKey = messageKey
            )
            "PAIRING_HANDSHAKE" -> EngineEvent.PairingReceived(
                senderName = senderName,
                senderTopicId = senderTopicId,
                senderPublicKey = senderPublicKey,
                requiresReply = true,
                messageKey = messageKey
            )
            "PAIRING_HANDSHAKE_REPLY" -> EngineEvent.PairingReceived(
                senderName = senderName,
                senderTopicId = senderTopicId,
                senderPublicKey = senderPublicKey,
                requiresReply = false,
                messageKey = messageKey
            )
            "NAME_UPDATE" -> EngineEvent.NameUpdateReceived(
                newName = senderName,
                senderTopicId = senderTopicId,
                messageKey = messageKey
            )
            else -> EngineEvent.Ignored
        }
    }

    /**
     * Builds a JSON payload for an outbound emergency page.
     */
    fun buildPagePayload(
        targetTopicId: String,
        level: SharedPageLevel,
        messageText: String,
        timestamp: Long,
        senderPublicKey: String = "",
        signature: String = ""
    ): String {
        val map = buildJsonObject {
            put("type", "PAGE")
            put("senderName", myName)
            put("senderTopicId", myTopicId)
            put("senderPublicKey", senderPublicKey)
            put("level", level.code)
            put("timestamp", timestamp)
            put("ciphertext", messageText)
            put("signature", signature)
        }
        return json.encodeToString(JsonObject.serializer(), map)
    }

    /**
     * Builds a JSON payload for a page acknowledgment receipt.
     */
    fun buildAckPayload(
        timestamp: Long,
        senderPublicKey: String = "",
        signature: String = ""
    ): String {
        val map = buildJsonObject {
            put("type", "PAGE_ACK")
            put("senderName", myName)
            put("senderTopicId", myTopicId)
            put("senderPublicKey", senderPublicKey)
            put("level", SharedPageLevel.HEY_LOOK.code)
            put("timestamp", timestamp)
            put("ciphertext", "Alert Acknowledged by $myName")
            put("signature", signature)
        }
        return json.encodeToString(JsonObject.serializer(), map)
    }

    /**
     * Builds a JSON payload for pairing handshakes.
     */
    fun buildPairingPayload(
        isReply: Boolean,
        timestamp: Long,
        senderPublicKey: String = ""
    ): String {
        val map = buildJsonObject {
            put("type", if (isReply) "PAIRING_HANDSHAKE_REPLY" else "PAIRING_HANDSHAKE")
            put("senderName", myName)
            put("senderTopicId", myTopicId)
            put("senderPublicKey", senderPublicKey)
            put("timestamp", timestamp)
        }
        return json.encodeToString(JsonObject.serializer(), map)
    }

    /**
     * Helper to compute the clean Firebase RTDB endpoint for a given topic.
     */
    fun getDispatchUrl(topic: String): String {
        val cleanTopic = topic.trim().replace(Regex("^https?:/+[^/]+/"), "").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val cleanBase = if (relayServerUrl.isNotBlank()) (if (relayServerUrl.endsWith("/")) relayServerUrl else "$relayServerUrl/") else "https://paging-dr-howard-default-rtdb.firebaseio.com/"
        return if (cleanBase.contains("firebaseio.com")) {
            "${cleanBase}channels/$cleanTopic.json"
        } else {
            "$cleanBase$cleanTopic"
        }
    }
}
