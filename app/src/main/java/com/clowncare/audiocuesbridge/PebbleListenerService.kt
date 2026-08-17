package com.clowncare.audiocuesbridge

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Receives cue commands from the Pebble watchapp and hands them to the right handler.
 *
 * Cue commands go through the accessibility service, which needs Audio Cues in the foreground.
 * Volume commands go straight to AudioManager, so they work no matter what is on screen.
 *
 * The Pebble mobile app binds to this service while the watchapp is running, which is what keeps
 * the bridge awake during a show without a foreground notification of its own.
 */
class PebbleListenerService : BasePebbleListenerService() {

    private companion object {
        const val TAG = "AudioCuesBridge"
        const val MAX_MSG_CHARS = 38
        const val MAX_CUE_CHARS = 60
        const val SETTLE_MS = 150L
    }

    private var sender: PebbleSender? = null

    private val audio: AudioManager by lazy {
        applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.i(TAG, "watchapp opened on $watch")
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.i(TAG, "watchapp closed on $watch")
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != Cmd.WATCHAPP_UUID) return ReceiveResult.Nack

        val cmd = data.readInt(Cmd.KEY_CMD)
        if (cmd == null) {
            Log.w(TAG, "message with no CMD key: $data")
            return ReceiveResult.Nack
        }

        val bridge = AudioCuesBridgeService.instance

        var result = when {
            Cmd.isVolume(cmd) -> adjustVolume(cmd == Cmd.VOL_UP)
            bridge == null -> BridgeResult(Cmd.ST_NO_ACCESS, "Enable bridge in Accessibility")
            else -> bridge.execute(cmd)
        }

        // GO and cue navigation move the highlight, so let the UI settle and re-read the title.
        // That way the watch always shows the cue that is up next, not the one just fired.
        val movesHighlight = cmd == Cmd.GO || cmd == Cmd.NEXT || cmd == Cmd.PREV
        if (bridge != null && movesHighlight && result.status == Cmd.ST_OK) {
            delay(SETTLE_MS)
            result = result.copy(cue = bridge.currentCue() ?: result.cue)
        }

        // Every reply carries the current volume so the watch's bar never drifts out of date.
        if (result.volume == null) {
            result = result.copy(volume = volumePercent())
        }

        Log.i(TAG, "${Cmd.name(cmd)} -> status=${result.status} msg=${result.message}")
        reply(result)
        return ReceiveResult.Ack
    }

    // ---------------------------------------------------------------- volume

    private fun volumePercent(): Int? = try {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) null else (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max
    } catch (e: Exception) {
        Log.w(TAG, "could not read volume", e)
        null
    }

    private fun adjustVolume(up: Boolean): BridgeResult {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        return try {
            // Flag 0 means no system volume popup: the watch shows the level instead, so nothing
            // slides over Audio Cues mid-show.
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            val pct = volumePercent()
            BridgeResult(
                status = Cmd.ST_OK,
                message = pct?.let { "Volume $it%" } ?: "Volume changed",
                volume = pct,
            )
        } catch (e: SecurityException) {
            BridgeResult(Cmd.ST_ERROR, "Volume blocked by Do Not Disturb")
        } catch (e: Exception) {
            Log.w(TAG, "volume adjust failed", e)
            BridgeResult(Cmd.ST_ERROR, "Volume change failed")
        }
    }

    // ---------------------------------------------------------------- replying

    private suspend fun reply(result: BridgeResult) {
        val pebble = sender ?: DefaultPebbleSender(applicationContext).also { sender = it }
        val payload = buildMap<UInt, PebbleDictionaryItem> {
            put(Cmd.KEY_STATUS.toUInt(), PebbleDictionaryItem.UInt8(result.status))
            put(Cmd.KEY_MSG.toUInt(), PebbleDictionaryItem.Text(result.message.take(MAX_MSG_CHARS)))
            result.cue?.takeIf { it.isNotBlank() }?.let {
                put(Cmd.KEY_CUE.toUInt(), PebbleDictionaryItem.Text(it.take(MAX_CUE_CHARS)))
            }
            result.volume?.let {
                put(Cmd.KEY_VOL.toUInt(), PebbleDictionaryItem.UInt8(it.coerceIn(0, 100)))
            }
        }
        try {
            pebble.sendDataToPebble(Cmd.WATCHAPP_UUID, payload)
        } catch (e: Exception) {
            Log.w(TAG, "reply to watch failed", e)
        }
    }

    override fun onDestroy() {
        sender?.close()
        sender = null
        super.onDestroy()
    }
}

/**
 * Numbers arrive from the watch widened to 32 bits, but accept every width so the bridge keeps
 * working if the watchapp ever changes its encoding.
 */
private fun PebbleDictionary.readInt(key: Int): Int? = when (val item = this[key.toUInt()]) {
    is PebbleDictionaryItem.UInt8 -> item.value.toInt()
    is PebbleDictionaryItem.Int8 -> item.value.toInt()
    is PebbleDictionaryItem.UInt16 -> item.value.toInt()
    is PebbleDictionaryItem.Int16 -> item.value.toInt()
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    is PebbleDictionaryItem.Int32 -> item.value
    else -> null
}
