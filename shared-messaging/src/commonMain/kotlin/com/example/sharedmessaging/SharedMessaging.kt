// SharedMessaging.kt
package com.example.sharedmessaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Data class representing the pairing payload used by both the Android app and the web simulator.
 * The `fcmToken` field holds the Firebase Cloud Messaging registration token on the simulator side.
 */
@Serializable
data class PairingPayload(
    val id: String,
    val name: String,
    val fcmToken: String? = null,
    val publicKey: String = "",
    val passphrase: String = "",
    val serverUrl: String? = null
)

/**
 * Enum of supported message types. Extend as needed.
 */
@Serializable
enum class MessageType { PAGE, HEY_LOOK, SOS, PAIRING_HANDSHAKE, PAIRING_HANDSHAKE_REPLY, NAME_UPDATE, PAGE_ACK }

/**
 * Sealed class hierarchy for typed messages.
 */
@Serializable
sealed class Message {
    abstract val type: MessageType
    abstract val data: String
}

@Serializable
data class SimpleMessage(
    override val type: MessageType,
    override val data: String
) : Message()

object SharedMessaging {
    private val json = Json { encodeDefaults = true }

    /** Create a JSON string for a pairing payload. */
    @JvmStatic
    fun createPairingPayload(id: String, name: String, fcmToken: String?): String {
        val payload = PairingPayload(id = id, name = name, fcmToken = fcmToken)
        return json.encodeToString(payload)
    }

    /** Serialize a typed message to JSON. */
    @JvmStatic
    fun serializeMessage(type: MessageType, data: String): String {
        val msg = SimpleMessage(type, data)
        return json.encodeToString(msg)
    }

    /** Deserialize a JSON string into a [Message] instance. */
    @JvmStatic
    fun deserializeMessage(jsonString: String): Message {
        return json.decodeFromString<SimpleMessage>(jsonString)
    }
}
