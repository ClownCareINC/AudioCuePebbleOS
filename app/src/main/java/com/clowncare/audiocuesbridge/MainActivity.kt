package com.clowncare.audiocuesbridge

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

/**
 * Setup screen for the bridge. Nothing here runs during a show: once accessibility is on and the
 * mappings are saved, the watch talks to PebbleListenerService directly.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TEST_DELAY_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusAccessibility: TextView
    private lateinit var statusAudioCues: TextView
    private lateinit var mappingContainer: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusAudioCues = findViewById(R.id.statusAudioCues)
        mappingContainer = findViewById(R.id.mappingContainer)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenAudioCues).setOnClickListener { openAudioCues() }
        findViewById<Button>(R.id.btnTestGo).setOnClickListener { runTest(Cmd.GO) }
        findViewById<Button>(R.id.btnTestStop).setOnClickListener { runTest(Cmd.STOP_ALL) }
        findViewById<Button>(R.id.btnTestNext).setOnClickListener { runTest(Cmd.NEXT) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
                val sel = Prefs.selector(this@MainActivity, key)
                text = sel ?: "not set (auto-detect)"
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
        val bridge = AudioCuesBridgeService.instance
        if (bridge == null) {
            toast("Turn on the accessibility service first.")
            return
        }
        if (!openAudioCues()) return

        log("${Cmd.name(cmd)} in ${TEST_DELAY_MS / 1000}s...")
        handler.postDelayed({
            val result = AudioCuesBridgeService.instance?.execute(cmd)
                ?: BridgeResult(Cmd.ST_NO_ACCESS, "Accessibility service went away")
            log("${Cmd.name(cmd)}: ${result.message} (status ${result.status})")
            if (result.cue != null) log("  cue on screen: ${result.cue}")
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
        }.lineSequence().take(12).joinToString("\n")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(0x33888888)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
