package com.moinstudioz.advanceplay

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.TextUtils
import android.text.format.Formatter
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
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Your downloaded videos: tap to stream, hold to delete. */
class LibraryActivity : ComponentActivity() {
    private val items = ArrayList<Library.Item>()
    private lateinit var list: ListView
    private lateinit var head: TextView
    private lateinit var empty: TextView
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
            val v = items[i]
            val row = LinearLayout(this@LibraryActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                isFocusable = true; isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(0xFF1F1F1F.toInt(), Color.WHITE, 3))
                    addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF1F1F1F.toInt(), 0xFF3A3A3A.toInt(), 1))
                    addState(intArrayOf(), shape(0xFF141414.toInt(), 0xFF262626.toInt(), 1))
                }
                setOnClickListener {
                    startActivity(Intent(this@LibraryActivity, PlayerActivity::class.java)
                        .putExtra("uri", v.uri.toString()).putExtra("name", v.name))
                }
                setOnLongClickListener { confirmDelete(v); true }
            }
            val ic = ImageView(this@LibraryActivity).apply {
                setImageResource(R.drawable.ic_play); imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2A2A.toInt()) }
            }
            row.addView(ic, dp(48), dp(48))
            val col = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            col.addView(TextView(this@LibraryActivity).apply {
                text = v.name.substringBeforeLast('.'); setTextColor(Color.WHITE); textSize = 16f
                typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this@LibraryActivity).apply {
                text = Formatter.formatFileSize(this@LibraryActivity, v.size) + "  \u00B7  " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(v.modified))
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
        head = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(18), dp(20), dp(10)); text = "Library"
        }
        col.addView(head, MATCH, WRAP)
        list = ListView(this).apply {
            divider = null; dividerHeight = dp(10); setPadding(dp(14), dp(4), dp(14), dp(14)); clipToPadding = false
            itemsCanFocus = true; adapter = this@LibraryActivity.adapter; selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        empty = TextView(this).apply {
            setTextColor(0xFF9A9A9A.toInt()); textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE
            text = "No videos yet.\nDownloaded videos appear here."
        }
        root.addView(col, MATCH, MATCH)
        root.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        Thread {
            val l = try { Library.list(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                items.clear(); items.addAll(l); adapter.notifyDataSetChanged()
                val total = l.sumOf { it.size }
                head.text = "Library  \u00B7  ${l.size} video" + (if (l.size == 1) "" else "s") +
                    (if (l.isNotEmpty()) "  \u00B7  " + Formatter.formatFileSize(this, total) else "")
                empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmDelete(it: Library.Item) {
        AlertDialog.Builder(this).setTitle("Delete this video?").setMessage(it.name)
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    try {
                        if (it.uri.scheme == "file") File(it.uri.path!!).delete()
                        else if (it.uri.authority == "media") contentResolver.delete(it.uri, null, null)
                        else DocumentsContract.deleteDocument(contentResolver, it.uri)
                    } catch (_: Exception) {}
                    runOnUiThread { reload() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }
}
