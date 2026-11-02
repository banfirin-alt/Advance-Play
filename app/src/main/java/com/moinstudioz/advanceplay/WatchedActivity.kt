package com.moinstudioz.advanceplay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date

/**
 * Every YouTube video that was played in Advance Play, with how much of it was watched.
 * Tap = continue where you stopped. Hold OK / long press = start again from 0:00.
 */
class WatchedActivity : ComponentActivity() {
    private val items = ArrayList<History.Watch>()
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }
    private fun clock(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) String.format("%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) else String.format("%d:%02d", t / 60, t % 60)
    }

    private fun play(w: History.Watch, fromStart: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("yt", "https://www.youtube.com/watch?v=" + w.id)
            .putExtra("name", w.title).putExtra("thumb", w.thumb).putExtra("fromStart", fromStart))
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
            val w = items[i]
            val row = LinearLayout(this@WatchedActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(16), dp(10))
                isFocusable = true; isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(0xFF1F1F1F.toInt(), Color.WHITE, 3))
                    addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF1F1F1F.toInt(), 0xFF3A3A3A.toInt(), 1))
                    addState(intArrayOf(), shape(0xFF141414.toInt(), 0xFF262626.toInt(), 1))
                }
                setOnClickListener { play(w, false) }
                setOnLongClickListener { play(w, true); true }          // long press: start from 0:00
            }
            val img = ImageView(this@WatchedActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt())
            }
            Thumbs.load(w.thumb, img)
            row.addView(img, dp(128), dp(72))
            val col = LinearLayout(this@WatchedActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            col.addView(TextView(this@WatchedActivity).apply {
                text = w.title.ifBlank { "YouTube video" }; setTextColor(Color.WHITE); textSize = 15f
                typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            // progress bar: green part = how much was watched
            val bar = LinearLayout(this@WatchedActivity).apply { setBackgroundColor(0xFF333333.toInt()) }
            val pct = w.percent.coerceIn(0, 100)
            bar.addView(View(this@WatchedActivity).apply { setBackgroundColor(if (w.finished) 0xFF3DDC84.toInt() else 0xFFFF3B30.toInt()) },
                LinearLayout.LayoutParams(0, MATCH, pct.toFloat()))
            bar.addView(View(this@WatchedActivity), LinearLayout.LayoutParams(0, MATCH, (100 - pct).toFloat()))
            col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(4)).apply { setMargins(0, dp(8), 0, dp(6)) })
            col.addView(TextView(this@WatchedActivity).apply {
                text = (if (w.finished) "\u2714 Finished  \u00B7  " + clock(w.durMs)
                        else "Watched " + clock(w.posMs) + (if (w.durMs > 0) " of " + clock(w.durMs) + "  (" + pct + "%)" else "")) +
                    "  \u00B7  " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(w.at))
                setTextColor(0xFF9A9A9A.toInt()); textSize = 12f
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            return row
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val e = if (Dev.isTv(this)) dp(32) else 0
            col.setPadding(i.left + e, i.top + e, i.right + e, i.bottom + e); ins
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(6)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; text = "Watched on YouTube"
        })
        titles.addView(TextView(this).apply {
            setTextColor(0xFF9A9A9A.toInt()); textSize = 12f; text = "Tap to continue  \u00B7  Hold to start from 0:00"
        })
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this).apply {
            text = "Clear all"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(0xFF1F1F1F.toInt(), Color.WHITE, 3))
                addState(intArrayOf(), shape(0xFF141414.toInt(), 0xFF555555.toInt(), 1))
            }
            setOnClickListener {
                AlertDialog.Builder(this@WatchedActivity).setTitle("Clear the watched list?")
                    .setMessage("Your downloaded videos are not touched.")
                    .setPositiveButton("Clear") { _, _ -> History.clearWatch(this@WatchedActivity); reload() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, WRAP, WRAP)
        col.addView(head, MATCH, WRAP)
        list = ListView(this).apply {
            divider = null; dividerHeight = dp(10); setPadding(dp(14), dp(4), dp(14), dp(14)); clipToPadding = false
            itemsCanFocus = true; adapter = this@WatchedActivity.adapter; selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        empty = TextView(this).apply {
            setTextColor(0xFF9A9A9A.toInt()); textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE
            text = "No watched videos yet.\nYouTube videos you play in Advance Play appear here."
        }
        root.addView(col, MATCH, MATCH)
        root.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        items.clear()
        items.addAll(History.watchList(this))
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
