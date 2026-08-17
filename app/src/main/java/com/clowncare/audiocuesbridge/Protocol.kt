package com.clowncare.audiocuesbridge

import java.util.UUID

/**
 * Shared protocol between the Pebble watchapp (src/c/main.c) and this bridge.
 * Keep the numbers in sync with the watchapp's package.json "messageKeys".
 */
object Cmd {
    val WATCHAPP_UUID: UUID = UUID.fromString("0a7c1f3e-9b6d-4c2a-8f51-3d6e7a2b4c90")

    const val AUDIO_CUES_PKG = "org.radialtheater.audiocues"

    // AppMessage keys
    const val KEY_CMD = 1
    const val KEY_STATUS = 2
    const val KEY_CUE = 3
    const val KEY_MSG = 4

    // watch -> phone commands
    const val GO = 1
    const val NEXT = 2
    const val PREV = 3
    const val STOP_ALL = 4
    const val PING = 5

    // phone -> watch status
    const val ST_ERROR = 0
    const val ST_OK = 1
    const val ST_NO_APP = 2
    const val ST_NO_ACCESS = 3
    const val ST_UNMAPPED = 4

    fun name(cmd: Int): String = when (cmd) {
        GO -> "GO"
        NEXT -> "Next cue"
        PREV -> "Prev cue"
        STOP_ALL -> "Stop All"
        PING -> "Ping"
        else -> "Unknown"
    }
}

/**
 * @param status one of Cmd.ST_*
 * @param message short human-readable text shown on the watch (keep under ~38 chars)
 * @param cue text of the currently highlighted cue in Audio Cues, if we could read it
 */
data class BridgeResult(
    val status: Int,
    val message: String,
    val cue: String? = null,
)
