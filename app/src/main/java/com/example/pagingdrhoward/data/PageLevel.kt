package com.example.pagingdrhoward.data

enum class PageLevel(
    val code: String,
    val title: String,
    val isLoopingSound: Boolean,
    val colorHex: Long
) {
    HEY_LOOK(
        code = "HEY_LOOK",
        title = "Hey look! 👀",
        isLoopingSound = true,
        colorHex = 0xFF1976D2 // Blue
    ),
    SOS(
        code = "SOS",
        title = "SOS EMERGENCY 🚨",
        isLoopingSound = true,
        colorHex = 0xFFD32F2F // Deep Red
    );

    companion object {
        fun fromCode(code: String?): PageLevel {
            return entries.find { it.code == code } ?: SOS
        }
    }
}
