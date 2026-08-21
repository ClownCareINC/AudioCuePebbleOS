package com.clowncare.audiocuesbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The half of the bridge that actually presses buttons.
 *
 * Android does not let one app inject key strokes into another, so we cannot fake the
 * MEDIA_PLAY_PAUSE keystroke that Audio Cues listens for. What we can do, with the user's
 * explicit permission, is use an AccessibilityService to find the GO / Stop All controls
 * inside Audio Cues and click them directly. Same end result, more reliable.
 *
 * Scope is deliberately narrow: the service is restricted to the Audio Cues package in
 * accessibility_service_config.xml, and nothing leaves the phone.
 */
class AudioCuesBridgeService : AccessibilityService() {

    companion object {
        private const val TAG = "AudioCuesBridge"
        private const val CACHE_THROTTLE_MS = 400L
        private const val MAX_CANDIDATES = 250
        private const val MAX_NODES = 4000

        @Volatile
        var instance: AudioCuesBridgeService? = null
            private set

        private val DEFAULT_GO = listOf("GO", "Go")
        private val DEFAULT_STOP = listOf("STOP ALL", "Stop All", "Stop all", "STOP", "Stop")
    }

    /** Snapshot of a control inside Audio Cues, used by the mapping screen. */
    data class Candidate(
        val viewId: String?,
        val text: String?,
        val desc: String?,
        val className: String?,
        val clickable: Boolean,
        val bounds: Rect,
    ) {
        fun selector(): String = when {
            !viewId.isNullOrBlank() -> "id:$viewId"
            !desc.isNullOrBlank() -> "desc:$desc"
            !text.isNullOrBlank() -> "text:$text"
            else -> "xy:${bounds.centerX()},${bounds.centerY()}"
        }

        fun label(): String {
            val main = listOf(text, desc)
                .firstOrNull { !it.isNullOrBlank() }
                ?: viewId?.substringAfterLast('/')
                ?: className?.substringAfterLast('.')
                ?: "view"
            val extra = buildString {
                if (clickable) append("tappable  ")
                append("${bounds.centerX()},${bounds.centerY()}")
            }
            return "$main\n$extra"
        }
    }

    @Volatile
    var candidates: List<Candidate> = emptyList()
        private set

    private var lastCacheAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "bridge accessibility service connected")
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != Cmd.AUDIO_CUES_PKG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCacheAt < CACHE_THROTTLE_MS) return
        lastCacheAt = now
        rootInActiveWindow?.let { cacheCandidates(it) }
    }

    // ------------------------------------------------------------------ commands

    fun execute(cmd: Int): BridgeResult {
        val root = rootInActiveWindow
            ?: return BridgeResult(Cmd.ST_NO_APP, "Open Audio Cues on the phone")

        if (root.packageName != Cmd.AUDIO_CUES_PKG) {
            return BridgeResult(Cmd.ST_NO_APP, "Audio Cues is not in front")
        }

        cacheCandidates(root)
        val cue = readCueLabel(root)

        return when (cmd) {
            Cmd.PING -> BridgeResult(Cmd.ST_OK, "Ready", cue)
            Cmd.GO -> pressMapped(root, Prefs.KEY_GO, DEFAULT_GO, "GO", cue)
            Cmd.STOP_ALL -> pressMapped(root, Prefs.KEY_STOP, DEFAULT_STOP, "STOP ALL", cue)
            Cmd.NEXT -> step(root, +1, cue)
            Cmd.PREV -> step(root, -1, cue)
            else -> BridgeResult(Cmd.ST_ERROR, "Unknown command", cue)
        }
    }

    /** Re-read the highlighted cue, used after a Next/Prev so the watch shows the new one. */
    fun currentCue(): String? = rootInActiveWindow
        ?.takeIf { it.packageName == Cmd.AUDIO_CUES_PKG }
        ?.let { readCueLabel(it) }

    private fun pressMapped(
        root: AccessibilityNodeInfo,
        prefKey: String,
        fallbackLabels: List<String>,
        what: String,
        cue: String?,
    ): BridgeResult {
        Prefs.selector(this, prefKey)?.let { sel ->
            if (sel.startsWith("xy:")) {
                val (x, y) = parseXy(sel) ?: return BridgeResult(Cmd.ST_ERROR, "Bad $what mapping", cue)
                return if (tapAt(x, y)) BridgeResult(Cmd.ST_OK, "$what sent", cue)
                else BridgeResult(Cmd.ST_ERROR, "$what tap failed", cue)
            }
            findBySelector(root, sel)?.let { return click(it, what, cue) }
        }

        for (label in fallbackLabels) {
            findByLabel(root, label)?.let { return click(it, what, cue) }
        }

        return BridgeResult(Cmd.ST_UNMAPPED, "Map $what in bridge app", cue)
    }

    /**
     * Move the highlighted cue up or down.
     *
     * Audio Cues moves its cue selection with the arrow keys, which we cannot send. Instead we
     * find the cue list, work out which row is currently selected, and click the neighbouring
     * row. If Audio Cues does not expose a selected row, fall back to a mapped control, then to
     * plain list scrolling.
     */
    private fun step(root: AccessibilityNodeInfo, delta: Int, cue: String?): BridgeResult {
        val what = if (delta > 0) "Next cue" else "Prev cue"
        val prefKey = if (delta > 0) Prefs.KEY_NEXT else Prefs.KEY_PREV

        Prefs.selector(this, prefKey)?.let { sel ->
            if (sel.startsWith("xy:")) {
                val (x, y) = parseXy(sel) ?: return BridgeResult(Cmd.ST_ERROR, "Bad $what mapping", cue)
                return if (tapAt(x, y)) BridgeResult(Cmd.ST_OK, "$what sent", cue)
                else BridgeResult(Cmd.ST_ERROR, "$what tap failed", cue)
            }
            findBySelector(root, sel)?.let { return click(it, what, cue) }
        }

        val list = findCueList(root)
            ?: return BridgeResult(Cmd.ST_UNMAPPED, "Cue list not found", cue)

        val rows = (0 until list.childCount).mapNotNull { list.getChild(it) }
        val current = rows.indexOfFirst { it.isSelected || it.isAccessibilityFocused }

        if (current < 0) {
            val action = if (delta > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            return if (list.performAction(action)) {
                BridgeResult(Cmd.ST_OK, "Scrolled cue list", cue)
            } else {
                BridgeResult(Cmd.ST_UNMAPPED, "Map $what in bridge app", cue)
            }
        }

        val target = rows.getOrNull(current + delta)
        if (target == null) {
            val edge = if (delta > 0) "End of cue list" else "Top of cue list"
            return BridgeResult(Cmd.ST_ERROR, edge, cue)
        }
        return click(target, what, cue)
    }

    // ------------------------------------------------------------------ clicking

    private fun click(node: AccessibilityNodeInfo, what: String, cue: String?): BridgeResult {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return BridgeResult(Cmd.ST_OK, "$what sent", cue)
                }
                break
            }
            n = n.parent
            hops++
        }

        // Some custom-drawn controls are not reported as clickable. Tap their centre instead.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() > 0 && r.height() > 0 && tapAt(r.centerX(), r.centerY())) {
            return BridgeResult(Cmd.ST_OK, "$what sent", cue)
        }
        return BridgeResult(Cmd.ST_ERROR, "$what failed", cue)
    }

    private fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // ------------------------------------------------------------------ finding

    private fun findBySelector(root: AccessibilityNodeInfo, selector: String): AccessibilityNodeInfo? {
        val value = selector.substringAfter(':')
        return when {
            selector.startsWith("id:") ->
                root.findAccessibilityNodeInfosByViewId(value)?.firstOrNull { it.isVisibleToUser }
                    ?: root.findAccessibilityNodeInfosByViewId(value)?.firstOrNull()

            selector.startsWith("desc:") -> traverse(root) { it.contentDescription?.toString() == value }
            selector.startsWith("text:") -> traverse(root) { it.text?.toString() == value }
            else -> null
        }
    }

    /** Match a visible label or content description, exact first then case-insensitive. */
    private fun findByLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        traverse(root) { it.text?.toString() == label || it.contentDescription?.toString() == label }
            ?.let { return it }
        return traverse(root) {
            it.text?.toString().equals(label, ignoreCase = true) ||
                it.contentDescription?.toString().equals(label, ignoreCase = true)
        }
    }

    /** The biggest scrollable container on screen is the cue list. */
    private fun findCueList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        nodes(root)
            .filter { it.isScrollable && it.childCount > 0 }
            .maxByOrNull { node ->
                val r = Rect().also { node.getBoundsInScreen(it) }
                r.width() * r.height()
            }

    /**
     * Work out which cue to show on the watch, trying the most reliable source first.
     *
     * 1. Whatever you mapped by hand. Always wins.
     * 2. A view whose id looks like a now-playing label (contains cue/title/track/playing).
     * 3. The biggest text in the control panel, meaning the bottom strip below the cue list.
     * 4. The highlighted row in the cue list, if Audio Cues marks one as selected.
     *
     * Step 4 was the only rule in the first version, and it almost never fired because
     * Android list rows rarely report isSelected.
     */
    private fun readCueLabel(root: AccessibilityNodeInfo): String? {
        Prefs.selector(this, Prefs.KEY_CUE_LABEL)?.let { sel ->
            findBySelector(root, sel)?.let { node ->
                collectText(node).takeIf { it.isNotBlank() }?.let { return it.trim().take(60) }
            }
        }

        val all = nodes(root)

        // 2. id-based guess
        all.firstOrNull { node ->
            val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase() ?: return@firstOrNull false
            (id.contains("cue") || id.contains("title") || id.contains("track") || id.contains("playing")) &&
                !node.text.isNullOrBlank()
        }?.let { return it.text.toString().trim().take(60) }

        // 3. control panel: the widest text sitting below the cue list
        val list = findCueList(root)
        val listBottom = list?.let { Rect().also { r -> it.getBoundsInScreen(r) }.bottom } ?: 0
        val screenBottom = Rect().also { root.getBoundsInScreen(it) }.bottom
        if (listBottom in 1 until screenBottom) {
            all.mapNotNull { node ->
                val text = node.text?.toString()?.trim()
                if (text.isNullOrBlank() || text.length < 2) return@mapNotNull null
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.top < listBottom || r.width() <= 0) return@mapNotNull null
                r.width() to text
            }.maxByOrNull { it.first }?.let { return it.second.take(60) }
        }

        // 4. selected row in the cue list
        val rows = (0 until (list?.childCount ?: 0)).mapNotNull { list?.getChild(it) }
        rows.firstOrNull { it.isSelected || it.isAccessibilityFocused }?.let {
            return collectText(it).trim().take(60).takeIf { t -> t.isNotBlank() }
        }

        return null
    }

    /**
     * Every text-bearing node on the Audio Cues screen, with ids and positions.
     * Used by the bridge app's "Dump Audio Cues screen" button so a human can see
     * exactly which view holds the playing cue, instead of us guessing.
     */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "No active window."
        if (root.packageName != Cmd.AUDIO_CUES_PKG) {
            return "Frontmost app is ${root.packageName}, not Audio Cues."
        }
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val list = findCueList(root)
        val listBounds = list?.let { Rect().also { r -> it.getBoundsInScreen(r) } }

        val sb = StringBuilder()
        sb.append("screen ${screen.width()}x${screen.height()}\n")
        sb.append("cue list: ${listBounds ?: "not found"}\n")
        sb.append("--- text nodes, top to bottom ---\n")

        nodes(root)
            .mapNotNull { node ->
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                if (text.isNullOrBlank() && desc.isNullOrBlank()) return@mapNotNull null
                val r = Rect().also { node.getBoundsInScreen(it) }
                val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
                r.top to "y=${r.top}-${r.bottom} id=$id sel=${node.isSelected} " +
                    "txt=${text ?: ""} desc=${desc ?: ""}"
            }
            .sortedBy { it.first }
            .forEach { sb.append(it.second).append('\n') }

        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo): String =
        nodes(node)
            .mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

    // ------------------------------------------------------------------ traversal

    private fun traverse(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = nodes(root).firstOrNull(predicate)

    /** Breadth-first flatten of the view tree, with a hard cap so a weird tree cannot hang us. */
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val node = queue.removeFirst()
            out.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    private fun cacheCandidates(root: AccessibilityNodeInfo) {
        candidates = nodes(root)
            .mapNotNull { node ->
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (!node.isClickable && text.isNullOrBlank() && desc.isNullOrBlank()) return@mapNotNull null
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width() <= 0 || r.height() <= 0) return@mapNotNull null
                Candidate(
                    viewId = node.viewIdResourceName,
                    text = text,
                    desc = desc,
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    bounds = r,
                )
            }
            .take(MAX_CANDIDATES)
    }

    private fun parseXy(selector: String): Pair<Int, Int>? {
        val parts = selector.removePrefix("xy:").split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        return x to y
    }
}
