package com.mel.stocks

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The app's main screen: the widget's list with every column. Symbol stays pinned on the left; everything else
 * scrolls sideways under a header that scrolls with it. Return columns are total returns (dividends included).
 */
class WatchlistActivity : Activity() {

    private data class Col(val key: String, val title: String, val widthDp: Int, val numeric: Boolean)

    private val cols = listOf(
        Col(NAME, "Name", 160, false),
        Col(PRICE, "Last", 84, true),
    ) + History.Span.values().map { Col(it.label, it.label, 64, true) }

    private lateinit var status: TextView
    private lateinit var symHeader: TextView
    private lateinit var headScroll: HorizontalScrollView
    private lateinit var headRow: LinearLayout
    private lateinit var symCol: LinearLayout
    private lateinit var bodyScroll: HorizontalScrollView
    private lateinit var bodyRows: LinearLayout
    private var ink = Look.inkFor(false)
    private var updating = false
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            build()
            ready = true
        } catch (t: Throwable) {
            Diag.record(this, "list screen", t)
            setContentView(ScrollView(this).apply {
                fitsSystemWindows = true
                addView(TextView(this@WatchlistActivity).apply {
                    setPadding(dp(16), dp(48), dp(16), dp(16))
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextIsSelectable(true)
                    text = "List failed to open:\n\n" + (Diag.last(this@WatchlistActivity) ?: t.toString())
                })
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        render()
        // Quotes older than a minute, or history not yet fetched today for some ticker: refresh in the background.
        val snap = Store.load(this)
        val today = History.today().toString()
        val stale = System.currentTimeMillis() - snap.attemptAt > 60_000L ||
            Config.load(this).tickers.any { History.load(this, it.symbol)?.fetchedOn != today }
        if (stale) refresh()
    }

    private fun refresh() {
        if (updating) return
        updating = true
        render()
        val app = applicationContext
        thread(name = "list-refresh") {
            try {
                StockWidget.refresh(app)
                // If a widget refresh was already running, ours returned at once: wait for that one instead.
                var waited = 0
                while (StockWidget.refreshing() && waited < 60_000) { Thread.sleep(250); waited += 250 }
            } catch (t: Throwable) {
                Diag.record(app, "list refresh", t)
            }
            runOnUiThread {
                updating = false
                if (!isDestroyed) render()
            }
        }
    }

    private fun build() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        ink = Look.inkFor(dark)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), 0)
        }
        bar.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink.primary)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(icon("⟳", "Refresh") { refresh() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(icon("⚙", "Settings") { openSettings() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(ink.muted)
            setPadding(dp(16), 0, dp(16), dp(10))
            setOnClickListener { openSettings() }
        }
        root.addView(status)

        // Header: pinned "Symbol" + a strip that follows the body's sideways scroll.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        symHeader = headerCell(SYMBOL, "Symbol", SYM_W, numeric = false)
        head.addView(symHeader)
        headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(headRow)
        }
        head.addView(headScroll, LinearLayout.LayoutParams(0, dp(34), 1f))
        root.addView(head)
        root.addView(line(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        symCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(symCol, LinearLayout.LayoutParams(dp(SYM_W), LinearLayout.LayoutParams.WRAP_CONTENT))
        bodyRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(bodyRows)
        }
        content.addView(bodyScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val vScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        root.addView(vScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        bodyScroll.setOnScrollChangeListener { _, x, _, _, _ -> if (headScroll.scrollX != x) headScroll.scrollTo(x, 0) }
        headScroll.setOnScrollChangeListener { _, x, _, _, _ -> if (bodyScroll.scrollX != x) bodyScroll.scrollTo(x, 0) }
    }

    private fun render() {
        try {
            draw()
        } catch (t: Throwable) {
            Diag.record(this, "list render", t)
            status.text = "Error: ${t.javaClass.simpleName}${t.message?.let { " – $it" } ?: ""} · tap for details"
        }
    }

    private fun draw() {
        val cfg = Config.load(this)
        val snap = Store.load(this)
        status.text = StockWidget.strip(this, snap, updating) + " · returns include dividends"

        val sort = TableSort.load(this)
        fun title(key: String, t: String) = t + if (sort.key == key) (if (sort.desc) " ▼" else " ▲") else ""
        symHeader.text = title(SYMBOL, "Symbol")
        symHeader.setTextColor(if (sort.key == SYMBOL) ink.primary else ink.muted)
        headRow.removeAllViews()
        for (c in cols) {
            val h = headerCell(c.key, title(c.key, c.title), c.widthDp, c.numeric)
            if (sort.key == c.key) h.setTextColor(ink.primary)
            headRow.addView(h)
        }

        symCol.removeAllViews()
        bodyRows.removeAllViews()
        val tickers = TableSort.apply(this, cfg.tickers, snap)
        if (tickers.isEmpty()) {
            symCol.addView(TextView(this).apply {
                text = "No tickers · tap ⚙ to add"
                textSize = 14f
                setTextColor(ink.muted)
                setPadding(dp(16), dp(16), 0, 0)
                isSingleLine = true
            }, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }

        for ((i, t) in tickers.withIndex()) {
            val q = snap.rows[t.symbol]
            val live = q != null && !q.stale
            val open = View.OnClickListener { openQuote(cfg, t.symbol) }

            symCol.addView(cellView(t.symbol, SYM_W, end = false, color = ink.primary, size = 15f, medium = true).apply {
                setPadding(dp(16), 0, dp(4), 0)
                setOnClickListener(open)
                background = ripple()
            }, LinearLayout.LayoutParams(dp(SYM_W), dp(ROW_H)))

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setOnClickListener(open)
                background = ripple()
            }
            for (c in cols) {
                val v = when (c.key) {
                    NAME -> null
                    PRICE -> q?.price
                    else -> History.Span.of(c.key)?.let { History.totalReturn(this, t.symbol, it, q?.price, q?.pct) }
                }
                val view = when (c.key) {
                    NAME -> cellView(nameOf(t, q), c.widthDp, end = false, color = ink.muted, size = 13f)
                    PRICE -> cellView(v?.let(Fmt::price) ?: "—", c.widthDp, end = true,
                        color = if (live) ink.primary else ink.muted, size = 14f)
                    else -> cellView(v?.let(Fmt::pct) ?: (if (q?.price != null) "—" else ""), c.widthDp, end = true,
                        color = when {
                            v == null || !live -> ink.muted
                            v > 0.0049 -> ink.up
                            v < -0.0049 -> ink.down
                            else -> ink.muted
                        }, size = 14f)
                }
                row.addView(view)
            }
            bodyRows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(ROW_H)))

            if (i < tickers.size - 1) {
                symCol.addView(line(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
                bodyRows.addView(line(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
        // Room at the end of the sideways scroll so the 3Y column doesn't sit flush against the edge.
        bodyRows.setPadding(0, 0, dp(12), dp(24))
        headRow.setPadding(0, 0, dp(12), 0)
    }

    /** Your label if you set one, else the company's full name from the price history, else the quote source's. */
    private fun nameOf(t: Ticker, q: Store.Row?): String =
        t.label ?: History.load(this, t.symbol)?.name ?: q?.name ?: ""

    private fun openQuote(cfg: Config, symbol: String) {
        val url = cfg.tapUrlFor(symbol)
        if (url.isBlank()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: ActivityNotFoundException) { }
    }

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

    // --- view helpers ---

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private fun cellView(text: CharSequence, widthDp: Int, end: Boolean, color: Int, size: Float, medium: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL or (if (end) Gravity.END else Gravity.START)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.MATCH_PARENT)
        }

    private fun headerCell(key: String, title: String, widthDp: Int, numeric: Boolean) =
        cellView(title, widthDp, end = numeric, color = ink.muted, size = 12f).apply {
            if (key == SYMBOL) setPadding(dp(16), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(34))
            setOnClickListener {
                TableSort.tap(this@WatchlistActivity, key, numeric)
                render()
            }
        }

    private fun line() = View(this).apply { setBackgroundColor(ink.divider) }

    private fun icon(glyph: String, desc: String, onTap: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = desc
        setTextColor(ink.icon)
        background = ripple()
        setOnClickListener { onTap() }
    }

    private fun ripple(): android.graphics.drawable.Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = a.getDrawable(0)
        a.recycle()
        return d
    }

    companion object {
        const val SYMBOL = "SYMBOL"
        const val NAME = "NAME"
        const val PRICE = "PRICE"
        private const val SYM_W = 84
        private const val ROW_H = 46
    }
}

/** Sort state for the full-screen list, kept separate from the widget's. */
object TableSort {
    data class State(val key: String?, val desc: Boolean)

    private const val PREFS = "table_sort"

    fun load(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(p.getString("key", null), p.getBoolean("desc", true))
    }

    /** Numbers: high→low → low→high → your order. Text: A→Z → Z→A → your order. */
    fun tap(ctx: Context, key: String, numeric: Boolean) {
        val s = load(ctx)
        val first = numeric          // desc first for numbers, asc first for text
        val next = when {
            s.key != key -> State(key, first)
            s.desc == first -> State(key, !first)
            else -> State(null, true)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", next.key).putBoolean("desc", next.desc).apply()
    }

    fun apply(ctx: Context, tickers: List<Ticker>, snap: Store.Snapshot): List<Ticker> {
        val s = load(ctx)
        val key = s.key ?: return tickers
        if (key == WatchlistActivity.SYMBOL || key == WatchlistActivity.NAME) {
            fun text(t: Ticker) = if (key == WatchlistActivity.SYMBOL) t.symbol
            else (t.label ?: History.load(ctx, t.symbol)?.name ?: snap.rows[t.symbol]?.name ?: "")
            // Blank names stay at the bottom in your order, whichever way the rest sort.
            val (named, blank) = tickers.partition { text(it).isNotBlank() }
            val sorted = named.sortedBy { text(it).lowercase() }
            return (if (s.desc) sorted.reversed() else sorted) + blank
        }
        fun value(t: Ticker): Double? {
            val q = snap.rows[t.symbol] ?: return null
            if (key == WatchlistActivity.PRICE) return q.price
            val span = History.Span.of(key) ?: return null
            return History.totalReturn(ctx, t.symbol, span, q.price, q.pct)
        }
        val withVal = tickers.map { it to value(it) }
        val known = withVal.filter { it.second != null }
            .sortedBy { it.second!! }
            .let { if (s.desc) it.reversed() else it }
        return known.map { it.first } + withVal.filter { it.second == null }.map { it.first }
    }
}
