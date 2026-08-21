package com.clowncare.audiocuesbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Setup and diagnostics screen. Nothing here runs during a show: once accessibility is on and the
 * Pebble link checks out, the watch talks to PebbleListenerService directly.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TEST_DELAY_MS = 5000L
        const val WATCH_QUERY_TIMEOUT_MS = 3000L
        const val DUMP_DELAY_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private lateinit var statusPebble: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusAudioCues: TextView
    private lateinit var mappingContainer: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusPebble = findViewById(R.id.statusPebble)
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusAudioCues = findViewById(R.id.statusAudioCues)
        mappingContainer = findViewById(R.id.mappingContainer)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.btnPingWatch).setOnClickListener { pingWatch() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenAudioCues).setOnClickListener { openAudioCues() }
        findViewById<Button>(R.id.btnTestGo).setOnClickListener { runTest(Cmd.GO) }
        findViewById<Button>(R.id.btnTestStop).setOnClickListener { runTest(Cmd.STOP_ALL) }
        findViewById<Button>(R.id.btnTestNext).setOnClickListener { runTest(Cmd.NEXT) }
        findViewById<Button>(R.id.btnDumpScreen).setOnClickListener { dumpScreen() }
    }

    /**
     * Opens Audio Cues, waits for you to get a cue playing, then lists every piece of text
     * on that screen with its view id and position. Copies the result to the clipboard.
     */
    private fun dumpScreen() {
        if (AudioCuesBridgeService.instance == null) {
            toast("Turn on the accessibility service first.")
            return
        }
        if (!openAudioCues()) return

        log("Dumping screen in ${DUMP_DELAY_MS / 1000}s. Start a cue now.")
        handler.postDelayed({
            val dump = AudioCuesBridgeService.instance?.dumpScreen() ?: "Service went away"
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Audio Cues screen", dump))
            logView.text = dump
            toast("Copied to clipboard. Paste it to Claude.")
        }, DUMP_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshPebbleStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Pebble diagnostics

    /**
     * Answers the question "can the watch actually reach this app?" without guesswork.
     * If the watch says "Phone not reachable", the reason shows up here.
     */
    private fun refreshPebbleStatus() {
        statusPebble.text = "Checking..."
        scope.launch {
            val report = StringBuilder()

            val picker = DefaultPebbleAndroidAppPicker.getInstance(applicationContext)
            val eligible = runCatching { picker.getAllEligibleApps() }.getOrDefault(emptyList())
            report.append(
                if (eligible.isEmpty()) "Pebble app: NOT FOUND on this phone\n"
                else "Pebble app: ${eligible.joinToString()}\n"
            )

            val selected = runCatching {
                withTimeoutOrNull(WATCH_QUERY_TIMEOUT_MS) { picker.getCurrentlySelectedApp() }
            }.getOrNull()
            report.append("Selected: ${selected ?: "none"}\n")

            val watches = runCatching {
                withTimeoutOrNull(WATCH_QUERY_TIMEOUT_MS) {
                    DefaultPebbleInfoRetriever(applicationContext).getConnectedWatches().first()
                }
            }.getOrNull()
            report.append(
                when {
                    watches == null -> "Watches: could not read (is the Pebble app running?)"
                    watches.isEmpty() -> "Watches: none connected"
                    else -> "Watches: " + watches.joinToString { "${it.name} (${it.platform})" }
                }
            )

            statusPebble.text = report.toString()
        }
    }

    /**
     * Sends a message straight to the watchapp and reports exactly what came back.
     * FailedNoPermissions means this app is missing from the watchapp's companionApp list.
     * FailedDifferentAppOpen means the watchapp is not the app currently open on the watch.
     */
    private fun pingWatch() {
        log("Pinging watch...")
        scope.launch {
            val sender = DefaultPebbleSender(applicationContext)
            try {
                val payload = mapOf(
                    Cmd.KEY_STATUS.toUInt() to PebbleDictionaryItem.UInt8(Cmd.ST_OK),
                    Cmd.KEY_MSG.toUInt() to PebbleDictionaryItem.Text("Bridge test OK"),
                )
                val results = withTimeoutOrNull(WATCH_QUERY_TIMEOUT_MS * 3) {
                    sender.sendDataToPebble(Cmd.WATCHAPP_UUID, payload)
                }
                when {
                    results == null -> log("No reply. Pebble app unreachable or not installed.")
                    results.isEmpty() -> log("No watches connected.")
                    else -> results.forEach { (watch, result) -> log("  $watch: ${explain(result)}") }
                }
            } catch (e: Exception) {
                log("Ping failed: ${e.message}")
            } finally {
                runCatching { sender.close() }
            }
        }
    }

    private fun explain(result: TransmissionResult): String = when (result) {
        is TransmissionResult.Success -> "OK, watch received it"
        is TransmissionResult.FailedNoPermissions ->
            "REJECTED. This app is not in the watchapp's companionApp list."
        is TransmissionResult.FailedWatchNotConnected -> "Watch not connected"
        is TransmissionResult.FailedDifferentAppOpen -> "Open Audio Cues on the watch first"
        is TransmissionResult.FailedWatchNacked -> "Watch refused the message"
        is TransmissionResult.FailedTimeout -> "Timed out"
        else -> result.toString()
    }

    // ---------------------------------------------------------------- status

    private fun refresh() {
        val bridge = AudioCuesBridgeService.instance
        statusAccessibility.text = if (bridge != null) {
            "On. The watch can press buttons in Audio Cues."
        } else {
            "Off. Turn on \"Audio Cues Pebble Bridge\" under Settings > Accessibility > " +
                "Downloaded apps, then come back."
        }

        val installed = runCatching {
            packageManager.getLaunchIntentForPackage(Cmd.AUDIO_CUES_PKG) != null
        }.getOrDefault(false)
        statusAudioCues.text = if (installed) {
            "Audio Cues found. Put it in Run mode before your show."
        } else {
            "Audio Cues was not found on this phone."
        }

        buildMappingRows(bridge?.candidates ?: emptyList())
    }

    private fun buildMappingRows(candidates: List<AudioCuesBridgeService.Candidate>) {
        mappingContainer.removeAllViews()

        for (key in Prefs.ALL_KEYS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            val title = TextView(this).apply {
                text = Prefs.title(key)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val current = TextView(this).apply {
                text = Prefs.selector(this@MainActivity, key) ?: "not set (auto-detect)"
                textSize = 13f
            }

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pick = Button(this@MainActivity).apply {
                    text = "Pick control"
                    setOnClickListener { showPicker(key, candidates) }
                }
                val clear = Button(this@MainActivity).apply {
                    text = "Clear"
                    setOnClickListener {
                        Prefs.setSelector(this@MainActivity, key, null)
                        refresh()
                    }
                }
                addView(pick, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
                addView(clear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }

            row.addView(title)
            row.addView(current)
            row.addView(buttons)
            row.addView(divider())
            mappingContainer.addView(row)
        }
    }

    private fun showPicker(key: String, candidates: List<AudioCuesBridgeService.Candidate>) {
        if (candidates.isEmpty()) {
            toast("No controls captured yet. Open Audio Cues in Run mode, then return here.")
            return
        }
        val labels = candidates.map { it.label() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Prefs.title(key))
            .setItems(labels) { _, index ->
                Prefs.setSelector(this, key, candidates[index].selector())
                refresh()
                log("${Prefs.title(key)} -> ${candidates[index].selector()}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- testing

    private fun runTest(cmd: Int) {
        if (AudioCuesBridgeService.instance == null) {
            toast("Turn on the accessibility service first.")
            return
        }
        if (!openAudioCues()) return

        log("${Cmd.name(cmd)} in ${TEST_DELAY_MS / 1000}s...")
        handler.postDelayed({
            val result = AudioCuesBridgeService.instance?.execute(cmd)
                ?: BridgeResult(Cmd.ST_NO_ACCESS, "Accessibility service went away")
            log("${Cmd.name(cmd)}: ${result.message} (status ${result.status})")
            result.cue?.let { log("  cue on screen: $it") }
        }, TEST_DELAY_MS)
    }

    private fun openAudioCues(): Boolean {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(Cmd.AUDIO_CUES_PKG) }
            .getOrNull()
        if (intent == null) {
            toast("Audio Cues is not installed.")
            return false
        }
        startActivity(intent)
        return true
    }

    // ---------------------------------------------------------------- helpers

    private fun log(line: String) {
        logView.text = buildString {
            append(line)
            if (logView.text.isNotEmpty()) {
                append('\n')
                append(logView.text)
            }
        }.lineSequence().take(14).joinToString("\n")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(0x33888888)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
