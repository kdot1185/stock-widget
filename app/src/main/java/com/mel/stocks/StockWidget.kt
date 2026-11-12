package com.mel.stocks

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class StockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(context)
        try { Scheduler.ensure(context) } catch (t: Throwable) { Diag.record(context, "schedule", t) }
        val snap = Store.load(context)
        if (System.currentTimeMillis() - snap.attemptAt > 10 * 60_000L) refreshAsync(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        render(context)

    override fun onDisabled(context: Context) = Scheduler.cancel(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> refreshAsync(context, goAsync())
            ACTION_SORT_1D, ACTION_SORT_5D, ACTION_SORT_1M -> {
                Sort.tap(context, intent.action!!.substringAfterLast('_'))
                render(context)
            }
            ACTION_MORNING -> {
                try { Scheduler.scheduleMorning(context) } catch (t: Throwable) { Diag.record(context, "morning alarm", t) }
                refreshAsync(context, goAsync())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // A new build starts with a clean slate; old crash reports describe old code.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Diag.clear(context)
                try { Scheduler.ensure(context) } catch (t: Throwable) { Diag.record(context, "schedule", t) }
                render(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.mel.stocks.REFRESH"
        const val ACTION_MORNING = "com.mel.stocks.MORNING"
        const val ACTION_SORT_1D = "com.mel.stocks.SORT_1D"
        const val ACTION_SORT_5D = "com.mel.stocks.SORT_5D"
        const val ACTION_SORT_1M = "com.mel.stocks.SORT_1M"

        private val busy = AtomicBoolean(false)

        /** True while a refresh is running (another caller's refresh() returns immediately in that case). */
        fun refreshing() = busy.get()

        fun hasWidgets(ctx: Context) =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StockWidget::class.java)).isNotEmpty()

        fun refreshAsync(ctx: Context, pending: android.content.BroadcastReceiver.PendingResult?) {
            val app = ctx.applicationContext
            thread(name = "quote-refresh") {
                try { refresh(app) } finally { pending?.finish() }
            }
        }

        /** Blocking: fetch everything, store it, redraw. Concurrent calls collapse into one. */
        fun refresh(ctx: Context, allowAutoLogin: Boolean = true) {
            if (!busy.compareAndSet(false, true)) return
            try {
                render(ctx, updating = true)
                val cfg = Config.load(ctx)
                Store.record(ctx, Quotes.fetchAll(ctx, cfg))
                // Daily history for the return columns; at most one fetch per symbol per day, failures don't block quotes.
                try { History.refresh(ctx, cfg.tickers.map { it.symbol }.distinct()) } catch (t: Throwable) { Diag.record(ctx, "history", t) }
            } catch (e: AuthNeeded) {
                // Saved login → sign in unattended in a background job, which refreshes again when done.
                if (allowAutoLogin && AutoLogin.request(ctx)) Store.note(ctx, "Signing in to E*TRADE…")
                else Store.note(ctx, "${e.message} · tap here")
            } catch (e: Throwable) {
                // Keep the last good snapshot; the strip will show the old time.
                Diag.record(ctx, "refresh", e)
            } finally {
                busy.set(false)
                render(ctx)
            }
        }

        fun render(ctx: Context, updating: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StockWidget::class.java))
            if (ids.isEmpty()) return
            val rv = try {
                build(ctx, updating)
            } catch (t: Throwable) {
                // Never leave the widget blank or crash the app: say what broke, tap opens settings with the trace.
                Diag.record(ctx, "widget render", t)
                RemoteViews(ctx.packageName, R.layout.widget).apply {
                    setTextViewText(R.id.updated, "Error: ${t.javaClass.simpleName}${t.message?.let { " – $it" } ?: ""}")
                    setViewVisibility(R.id.list, View.GONE)
                    setViewVisibility(R.id.empty, View.VISIBLE)
                    setTextViewText(R.id.empty, "Tap for details")
                    val pi = PendingIntent.getActivity(
                        ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
                    )
                    setOnClickPendingIntent(R.id.updated, pi)
                    setOnClickPendingIntent(R.id.empty, pi)
                    setOnClickPendingIntent(R.id.refresh, broadcast(ctx, ACTION_REFRESH, 11))
                }
            }
            try {
                mgr.updateAppWidget(ids, rv)
            } catch (t: Throwable) {
                Diag.record(ctx, "updateAppWidget", t)
            }
        }

        private fun build(ctx: Context, updating: Boolean): RemoteViews {
            val cfg = Config.load(ctx)
            val snap = Store.load(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val ink = Look.ink(cfg)
            rv.setInt(R.id.bg, "setColorFilter", cfg.bgColor or 0xFF000000.toInt())
            rv.setInt(R.id.bg, "setImageAlpha", cfg.opacity.coerceIn(0, 100) * 255 / 100)
            rv.setTextColor(R.id.updated, ink.muted)
            rv.setTextColor(R.id.empty, ink.muted)
            rv.setInt(R.id.refresh, "setColorFilter", ink.icon)
            rv.setInt(R.id.strip_divider, "setBackgroundColor", ink.divider)
            for (h in intArrayOf(R.id.h_price, R.id.h_1d, R.id.h_5d, R.id.h_1m)) rv.setTextColor(h, ink.muted)
            // Tap a Δ header: sort high→low, tap again low→high, third tap back to your list order.
            val sort = Sort.load(ctx)
            for ((id, key, label, action, code) in listOf(
                Hdr(R.id.h_1d, "1D", "Δ1D", ACTION_SORT_1D, 21),
                Hdr(R.id.h_5d, "5D", "Δ5D", ACTION_SORT_5D, 22),
                Hdr(R.id.h_1m, "1M", "Δ1M", ACTION_SORT_1M, 23),
            )) {
                val arrow = if (sort.key == key) (if (sort.desc) " ▼" else " ▲") else ""
                rv.setTextViewText(id, label + arrow)
                if (sort.key == key) rv.setTextColor(id, ink.primary)
                rv.setOnClickPendingIntent(id, broadcast(ctx, action, code))
            }

            val settings = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
            )
            rv.setTextViewText(R.id.updated, strip(ctx, snap, updating))
            rv.setOnClickPendingIntent(R.id.updated, settings)
            rv.setOnClickPendingIntent(R.id.refresh, broadcast(ctx, ACTION_REFRESH, 11))

            val tickers = Sort.apply(ctx, cfg.tickers, Store.load(ctx))
            val sessions = tickers.mapNotNull { snap.rows[it.symbol]?.takeIf { r -> !r.stale }?.session }
            rv.setTextViewText(R.id.h_price, when {
                sessions.count { it == "PRE" } * 2 > tickers.size -> "Pre-mkt"
                sessions.count { it == "AH" } * 2 > tickers.size -> "After hrs"
                else -> "Last"
            })
            if (tickers.isEmpty()) {
                rv.setViewVisibility(R.id.list, View.GONE)
                rv.setViewVisibility(R.id.empty, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.empty, settings)
                return rv
            }
            rv.setViewVisibility(R.id.list, View.VISIBLE)
            rv.setViewVisibility(R.id.empty, View.GONE)

            // Rows get fill-ins on one template; the template targets our own trampoline (Android 14+ rule).
            rv.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 12, Intent(ctx, OpenQuoteActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )

            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
            for ((i, t) in tickers.withIndex()) {
                val q = snap.rows[t.symbol]
                val row = RemoteViews(ctx.packageName, R.layout.row)
                // Rows are recycled: set every property every time.
                row.setTextViewText(R.id.symbol, t.symbol)
                row.setTextColor(R.id.symbol, ink.primary)
                row.setTextColor(R.id.name, ink.muted)
                row.setTextColor(R.id.session, ink.muted)
                row.setInt(R.id.divider, "setBackgroundColor", ink.divider)
                val name = t.label ?: q?.name ?: ""
                row.setTextViewText(R.id.name, name)
                row.setViewVisibility(R.id.name, if (cfg.showNames && name.isNotEmpty()) View.VISIBLE else View.GONE)

                row.setTextViewText(R.id.price, q?.price?.let(::price) ?: "—")
                row.setTextColor(R.id.price, if (q == null || q.stale) ink.muted else ink.primary)

                val live = q != null && !q.stale
                fun cell(id: Int, v: Double?) {
                    row.setTextViewText(id, v?.let(::pctText) ?: "—".takeIf { q?.price != null } ?: "")
                    row.setTextColor(id, when {
                        v == null || !live -> ink.muted
                        v > 0.0049 -> ink.up
                        v < -0.0049 -> ink.down
                        else -> ink.muted
                    })
                }
                // Total returns: dividends that went ex inside the window are added back.
                cell(R.id.change, History.totalReturn(ctx, t.symbol, History.Span.D1, q?.price, q?.pct))
                cell(R.id.change5d, History.totalReturn(ctx, t.symbol, History.Span.D5, q?.price, q?.pct))
                cell(R.id.change1m, History.totalReturn(ctx, t.symbol, History.Span.M1, q?.price, q?.pct))

                // Session (PRE/AH) is shown once in the "Last" header, not per row, so it never squeezes the ticker.
                row.setViewVisibility(R.id.session, View.GONE)
                row.setViewVisibility(R.id.divider, if (i < tickers.size - 1) View.VISIBLE else View.INVISIBLE)
                row.setOnClickFillInIntent(R.id.row, Intent().putExtra(OpenQuoteActivity.EXTRA_SYMBOL, t.symbol))
                items.addItem(i.toLong(), row)
            }
            rv.setRemoteAdapter(R.id.list, items.build())
            return rv
        }

        internal fun strip(ctx: Context, s: Store.Snapshot, updating: Boolean): String {
            if (updating) return "Updating…"
            s.note?.let { return it }
            val why = s.err?.let { ": " + it.take(60) } ?: ""
            val base = if (s.updatedAt == 0L) {
                if (s.attemptAt > 0) "Refresh failed$why" else "Not updated yet"
            } else {
                val t = stamp(ctx, s.updatedAt)
                when {
                    s.failed > 0 && s.attemptAt > s.updatedAt -> "Refresh failed$why · last $t"
                    s.failed > 0 -> "Updated $t · ${s.failed} failed$why"
                    else -> "Updated $t"
                }
            }
            return if (Diag.last(ctx) != null) "⚠ App error logged · tap · $base" else base
        }

        private fun stamp(ctx: Context, millis: Long): String {
            val time = DateFormat.getTimeFormat(ctx).format(Date(millis))
            return SimpleDateFormat("MMM d", Locale.US).format(Date(millis)) + ", " + time
        }

        private fun pctText(v: Double): CharSequence = Fmt.pct(v)

        private fun price(p: Double): String = Fmt.price(p)

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun broadcast(ctx: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, StockWidget::class.java).setAction(action), FLAGS,
        )
    }
}

/** Periodic background refresh plus one exact alarm each morning so the pre-market number is waiting for you. */
object Scheduler {
    private const val JOB_ID = 7

    fun ensure(ctx: Context) {
        if (!StockWidget.hasWidgets(ctx)) { cancel(ctx); return }
        val cfg = Config.load(ctx)
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (cfg.intervalMin <= 0) {
            js.cancel(JOB_ID)
        } else {
            val want = maxOf(15, cfg.intervalMin) * 60_000L
            val cur = js.getPendingJob(JOB_ID)
            if (cur == null || cur.intervalMillis != want) {
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, RefreshJob::class.java))
                        .setPeriodic(want)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
        scheduleMorning(ctx)
    }

    fun scheduleMorning(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = StockWidget.broadcast(ctx, StockWidget.ACTION_MORNING, 13)
        val hm = Config.parseTime(Config.load(ctx).morning)
        if (hm == null || !StockWidget.hasWidgets(ctx)) { am.cancel(pi); return }
        val now = ZonedDateTime.now()
        var next = now.with(LocalTime.of(hm.first, hm.second))
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        val at = next.toInstant().toEpochMilli()
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        ctx.getSystemService(AlarmManager::class.java).cancel(StockWidget.broadcast(ctx, StockWidget.ACTION_MORNING, 13))
    }
}

private data class Hdr(val id: Int, val key: String, val label: String, val action: String, val code: Int)

/** Sort state for the widget list: which Δ column, which direction, or none (your list order). */
object Sort {
    data class State(val key: String?, val desc: Boolean)

    private const val PREFS = "sort"

    fun load(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(p.getString("key", null), p.getBoolean("desc", true))
    }

    /** Cycle: off → high→low → low→high → off. Switching columns starts at high→low. */
    fun tap(ctx: Context, key: String) {
        val s = load(ctx)
        val next = when {
            s.key != key -> State(key, true)
            s.desc -> State(key, false)
            else -> State(null, true)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", next.key).putBoolean("desc", next.desc).apply()
    }

    fun apply(ctx: Context, tickers: List<Ticker>, snap: Store.Snapshot): List<Ticker> {
        val s = load(ctx)
        val key = s.key ?: return tickers
        fun value(t: Ticker): Double? {
            val q = snap.rows[t.symbol] ?: return null
            val span = History.Span.of(key) ?: return null
            return History.totalReturn(ctx, t.symbol, span, q.price, q.pct)
        }
        val withVal = tickers.map { it to value(it) }
        val known = withVal.filter { it.second != null }
            .sortedBy { it.second!! }
            .let { if (s.desc) it.reversed() else it }
        // Tickers without a value keep their list order at the bottom.
        return known.map { it.first } + withVal.filter { it.second == null }.map { it.first }
    }
}

class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "quote-job") {
            StockWidget.refresh(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}

/** Invisible hop from a row tap to the quote page. */
class OpenQuoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val symbol = intent.getStringExtra(EXTRA_SYMBOL)
        val url = symbol?.let { Config.load(this).tapUrlFor(it) }
        if (!url.isNullOrBlank()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) { }
        }
        finish()
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
