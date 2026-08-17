package com.clowncare.audiocuesbridge

import android.content.Context

/**
 * Stores which on-screen control in Audio Cues each watch button should press.
 *
 * A "selector" is one of:
 *   id:<android view id>      most stable, used when Audio Cues exposes view ids
 *   desc:<content description> for icon buttons
 *   text:<visible label>       for text buttons such as GO
 *   xy:<x>,<y>                 last resort: tap these screen coordinates
 */
object Prefs {
    private const val FILE = "bridge_prefs"

    const val KEY_GO = "sel_go"
    const val KEY_STOP = "sel_stop"
    const val KEY_NEXT = "sel_next"
    const val KEY_PREV = "sel_prev"
    const val KEY_CUE_LABEL = "sel_cue_label"

    val ALL_KEYS = listOf(KEY_GO, KEY_STOP, KEY_NEXT, KEY_PREV, KEY_CUE_LABEL)

    fun title(key: String): String = when (key) {
        KEY_GO -> "GO button"
        KEY_STOP -> "Stop All button"
        KEY_NEXT -> "Next cue (optional)"
        KEY_PREV -> "Previous cue (optional)"
        KEY_CUE_LABEL -> "Cue label to show on watch (optional)"
        else -> key
    }

    fun selector(context: Context, key: String): String? =
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.takeIf { it.isNotBlank() }

    fun setSelector(context: Context, key: String, selector: String?) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .apply { if (selector == null) remove(key) else putString(key, selector) }
            .apply()
    }
}
