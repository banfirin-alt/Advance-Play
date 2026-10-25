package com.moinstudioz.advanceplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private class Ratio(c: Context) : FrameLayout(c) {
    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        super.onMeasure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ww * 9 / 16, MeasureSpec.EXACTLY))
    }
}

private class VH(val card: LinearLayout, val img: ImageView, val dur: TextView, val title: TextView, val sub: TextView) :
    RecyclerView.ViewHolder(card)

/** Fast native YouTube home for TV: search, categories, play, hold OK to download. */
class BrowseActivity : ComponentActivity() {
    private val RED = 0xFFFF3B30.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val tv by lazy { Dev.isTv(this) }
    private val cats = listOf(
        "Trending" to "trending videos today", "Music" to "music videos", "Live" to "live now", "Gaming" to "gaming",
        "News" to "news today", "Movies" to "movie trailers", "Sports" to "sports highlights", "Cooking" to "cooking recipes"
    )
    private val items = ArrayList<StreamInfoItem>()
    private var extractor: SearchExtractor? = null
    private var nextPage: Page? = null
    private var loading = false
    private var gen = 0
    private var seenDone = 0
    private var seenErr = 0
    private val h = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var tStatus: TextView
    private lateinit var tNet: TextView
    private lateinit var busy: TextView
    private lateinit var black: View
    private lateinit var card: LinearLayout
    private lateinit var cardT: TextView
    private lateinit var cardS: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun txt(sp: Float, c: Int) = TextView(this).apply { textSize = sp; setTextColor(c) }
    private fun shape(fill: Int, stroke: Int, w: Int, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }
    private fun focusBg(accent: Int, r: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(0xFF262626.toInt(), accent, 3, r))
        addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF262626.toInt(), 0xFF262626.toInt(), 1, r))
        addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 0, r))
    }

    // ---------- list ----------
    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val c = this@BrowseActivity
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(8))
                isFocusable = true; isClickable = true; isLongClickable = true
                background = focusBg(Color.WHITE, 18)
                setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(120).start() }
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            val frame = Ratio(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF1C1C1C.toInt()) }
                clipToOutline = true
            }
            val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(img, MATCH, MATCH)
            val dur = txt(11f, Color.WHITE).apply {
                typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xCC000000.toInt()) }
            }
            frame.addView(dur, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(8), dp(8)) })
            val title = txt(14f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(4), dp(8), dp(4), 0) }
            val sub = txt(12f, 0xFF9A9A9A.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(4), dp(2), dp(4), 0) }
            card.addView(frame, LinearLayout.LayoutParams(MATCH, WRAP)); card.addView(title); card.addView(sub)
            return VH(card, img, dur, title, sub)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val v = items[i]
            h.title.text = v.name
            h.sub.text = listOfNotNull(
                v.uploaderName?.takeIf { it.isNotBlank() },
                if (v.viewCount >= 0) views(v.viewCount) else null
            ).joinToString("  \u00B7  ")
            h.dur.text = if (v.duration > 0) dur(v.duration) else "LIVE"
            h.dur.setTextColor(if (v.duration > 0) Color.WHITE else RED)
            Thumbs.load(v.thumbnails.firstOrNull { it.height in 150..420 }?.url ?: v.thumbnails.lastOrNull()?.url, h.img)
            h.card.setOnClickListener {
                startActivity(Intent(this@BrowseActivity, PlayerActivity::class.java).putExtra("yt", v.url).putExtra("name", v.name))
            }
            h.card.setOnLongClickListener { download(v); true }
            if (i >= items.size - 6) more()
        }
    }

    private fun views(n: Long) = when {
        n >= 1_000_000 -> String.format("%.1fM views", n / 1e6)
        n >= 1_000 -> String.format("%.1fK views", n / 1e3)
        else -> "$n views"
    }
    private fun dur(s: Long) = if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)

    private fun download(v: StreamInfoItem) {
        val err = Dl.enqueue(this, v.url)
        if (err != null) { toast(err); if (Usb.check(this) != null) startActivity(Intent(this, SettingsActivity::class.java)) }
        else toast("Added to download queue")
    }

    // ---------- loading ----------
    private fun search(q: String) {
        val g = ++gen
        items.clear(); adapter.notifyDataSetChanged(); extractor = null; nextPage = null; loading = true
        busy.visibility = View.VISIBLE; busy.text = "Loading..."
        Thread {
            try {
                Np.init()
                val ex = ServiceList.YouTube.getSearchExtractor(q)
                ex.fetchPage()
                val page = ex.initialPage
                val list = page.items.filterIsInstance<StreamInfoItem>()
                runOnUiThread { if (g == gen) { extractor = ex; nextPage = page.nextPage; add(list) } }
            } catch (e: Exception) {
                runOnUiThread { if (g == gen) { busy.text = "Could not load: " + (e.message ?: "error") + "\nPress Home to retry" } }
            } finally { runOnUiThread { if (g == gen) loading = false } }
        }.start()
    }

    private fun more() {
        val ex = extractor ?: return
        val np = nextPage ?: return
        if (loading) return
        loading = true
        val g = gen
        Thread {
            try {
                val page = ex.getPage(np)
                val list = page.items.filterIsInstance<StreamInfoItem>()
                runOnUiThread { if (g == gen) { nextPage = page.nextPage; add(list) } }
            } catch (_: Exception) { runOnUiThread { if (g == gen) nextPage = null } }
            finally { runOnUiThread { loading = false } }
        }.start()
    }

    private fun add(list: List<StreamInfoItem>) {
        val start = items.size
        items.addAll(list)
        adapter.notifyItemRangeInserted(start, list.size)
        busy.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) busy.text = "Nothing found"
    }

    private fun askSearch() {
        val et = EditText(this).apply {
            hint = "Search YouTube"; setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val dlg = AlertDialog.Builder(this).setTitle("Search").setView(et)
            .setPositiveButton("Search") { _, _ -> et.text.toString().trim().let { if (it.isNotEmpty()) search(it) } }
            .setNegativeButton("Cancel", null).create()
        et.setOnEditorActionListener { _, _, _ ->
            et.text.toString().trim().let { if (it.isNotEmpty()) { dlg.dismiss(); search(it) } }; true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show(); et.requestFocus()
    }

    // ---------- UI ----------
    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF262626.toInt(), Color.WHITE, 3, 24))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF262626.toInt(), Color.WHITE, 2, 24))
            addState(intArrayOf(), shape(0xFF161616.toInt(), 0xFF2E2E2E.toInt(), 1, 24))
        }
        setOnClickListener { click() }
    }

    private fun navItem(icon: Int, label: String, c: Int, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(6)); isFocusable = true; isClickable = true
        background = focusBg(c, 22)
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(c) }, dp(30), dp(30))
        addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(c); gravity = Gravity.CENTER }, WRAP, WRAP)
        setOnClickListener { click() }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val e = if (tv) dp(28) else dp(8)
            content.setPadding(i.left + e, i.top + e, i.right + e, i.bottom + e); ins
        }

        // left rail
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = shape(0xFF141414.toInt(), 0xFF2A2A2A.toInt(), 1, 32)
        }
        fun item(icon: Int, label: String, c: Int, click: () -> Unit) =
            rail.addView(navItem(icon, label, c, click), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) })
        item(R.drawable.ic_home, "Home", Color.WHITE) { search(cats[0].second) }
        item(R.drawable.ic_search, "Search", Color.WHITE) { askSearch() }
        item(R.drawable.ic_library, "Library", Color.WHITE) { startActivity(Intent(this, LibraryActivity::class.java)) }
        item(R.drawable.ic_moon, "Background", Color.WHITE) { enterBlack() }
        item(R.drawable.ic_reload, "YouTube Web", Color.WHITE) { startActivity(Intent(this, MainActivity::class.java)) }
        item(R.drawable.ic_settings, "Settings", RED) { startActivity(Intent(this, SettingsActivity::class.java)) }
        content.addView(rail, LinearLayout.LayoutParams(dp(112), MATCH).apply { setMargins(0, 0, dp(12), 0) })

        // right side: categories, grid, status
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply { setPadding(0, dp(2), 0, dp(8)) }
        cats.forEach { (label, q) -> chips.addView(pill(label) { search(q) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, dp(8), 0) }) }
        right.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, MATCH, WRAP)

        val wdp = resources.configuration.screenWidthDp
        val span = if (tv) maxOf(2, (wdp - 150) / 250) else if (wdp >= 600) 3 else 2
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@BrowseActivity, span)
            adapter = this@BrowseActivity.adapter
            clipToPadding = false; clipChildren = false; setPadding(dp(4), dp(8), dp(4), dp(8)); itemAnimator = null
        }
        busy = txt(16f, 0xFF9A9A9A.toInt()).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val gridBox = FrameLayout(this)
        gridBox.addView(grid, MATCH, MATCH)
        gridBox.addView(busy, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        right.addView(gridBox, LinearLayout.LayoutParams(MATCH, 0, 1f))

        tStatus = txt(13f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
        tNet = txt(12f, RED)
        val wm = txt(11f, Color.WHITE).apply { text = "Created By Moin Studioz"; alpha = .65f; gravity = Gravity.END }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(6))
            background = shape(0xFF111111.toInt(), 0xFF2A2A2A.toInt(), 1, 22)
        }
        panel.addView(tStatus); panel.addView(tNet); panel.addView(wm, MATCH, WRAP)
        right.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) })
        content.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(content, MATCH, MATCH)

        // "download complete" card
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22)); visibility = View.GONE; elevation = dp(8).toFloat()
        }
        cardT = txt(24f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        cardS = txt(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        card.addView(cardT); card.addView(cardS)
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // jet-black Background Mode
        black = View(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; keepScreenOn = true
            isClickable = true; isLongClickable = true; isFocusable = true
            setOnLongClickListener { exitBlack(); true }
        }
        root.addView(black, MATCH, MATCH)
    }

    private fun hideBars(hide: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }
    private fun enterBlack() { black.visibility = View.VISIBLE; black.requestFocus(); hideBars(true) }
    private fun exitBlack() { black.visibility = View.GONE; hideBars(false); grid.requestFocus() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (black.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) exitBlack(); return true }
            if (k in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- life cycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (DL.lastDone.isEmpty()) DL.lastDone = Prefs.lastDone(this)
        Throttle.maxSpeed = Prefs.maxSpeed(this)
        seenDone = DL.doneSeq; seenErr = DL.errSeq
        buildUi(); setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (black.visibility == View.VISIBLE) exitBlack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        search(cats[0].second)
        if (Usb.check(this) != null) toast("Open Settings and press Use USB drive")
        resumeUnfinished()
        h.post(tick)
        Thread { Backup.save(this) }.start()
    }

    private fun resumeUnfinished() {
        if (DL.resumed) return
        DL.resumed = true
        if (DL.queue > 0 || DL.active || !Usb.hasTarget(this)) return
        val p = Prefs.pending(this)
        if (p.isEmpty()) return
        toast("Resuming ${p.size} unfinished download(s)")
        p.forEach { u -> runCatching { ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", u)) } }
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun fmt(b: Long) = Formatter.formatFileSize(this, b)
    private fun spd(b: Long) = Formatter.formatFileSize(this, b) + "/s"
    private val hideCard = Runnable { card.visibility = View.GONE }

    private fun showCard(t: String, s: String, err: Boolean) {
        cardT.text = t; cardS.text = s
        cardT.setTextColor(if (err) RED else Color.WHITE)
        card.background = shape(0xFF161616.toInt(), if (err) RED else Color.WHITE, 2, 28)
        card.visibility = View.VISIBLE
        h.removeCallbacks(hideCard); h.postDelayed(hideCard, 5000)
    }

    private val tick = object : Runnable {
        override fun run() {
            Net.tick(this@BrowseActivity)
            val limited = Throttle.streaming && !Throttle.maxSpeed
            val st = StringBuilder()
            if (DL.active) {
                st.append("\u2B07 ").append(DL.title.take(45)).append(" \u00B7 ").append(DL.pct).append("% \u00B7 ").append(spd(DL.speed))
                if (DL.queue > 1) st.append(" \u00B7 +").append(DL.queue - 1).append(" queued")
                if (limited) st.append(" \u00B7 limited to 20%")
            } else if (DL.lastDone.isNotEmpty()) st.append("\u2714 Saved: ").append(DL.lastDone.take(45))
            else st.append("Hold OK on a video to download it")
            val u = Usb.space(this@BrowseActivity)
            st.append("   \u2022   ").append(if (tv) "USB " else "Storage ").append(if (u == null) "not connected" else fmt(u.first) + " free")
            tStatus.text = st
            tNet.text = if (!Net.supported) "Network usage unavailable" else
                "Network \u2193 ${spd(Net.speed)} \u00B7 Session ${fmt(Net.session)} \u00B7 Total ${fmt(Prefs.total(this@BrowseActivity))}" +
                    (if (limited) "   \u00B7   Video 80% \u00B7 Downloads 20%" else "")
            if (DL.doneSeq != seenDone) { seenDone = DL.doneSeq; showCard("\u2714 Download Complete", DL.lastDone + "\nSaved to USB", false) }
            if (DL.errSeq != seenErr) { seenErr = DL.errSeq; showCard("Download failed", DL.errMsg, true) }
            h.postDelayed(this, 1000)
        }
    }
}
