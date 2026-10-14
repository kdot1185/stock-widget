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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

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

        private val busy = AtomicBoolean(false)


        fun hasWidgets(ctx: Context) =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StockWidget::class.java)).isNotEmpty()

        fun refreshAsync(ctx: Context, pending: android.content.BroadcastReceiver.PendingResult?) {
            val app = ctx.applicationContext
            thread(name = "quote-refresh") {
                try { refresh(app) } finally { pending?.finish() }
            }
        }

        /** Blocking: fetch everything, store it, redraw. Concurrent calls collapse into one. */
        fun refresh(ctx: Context) {
            if (!busy.compareAndSet(false, true)) return
            try {
                render(ctx, updating = true)
                val cfg = Config.load(ctx)
                Store.record(ctx, Quotes.fetchAll(ctx, cfg))
                // Daily closes for Δ5D / Δ1M; at most one fetch per symbol per day, failures don't block quotes.
                try { History.refresh(ctx, cfg.tickers.map { it.symbol }.distinct()) } catch (t: Throwable) { Diag.record(ctx, "history", t) }
            } catch (e: AuthNeeded) {
                Store.note(ctx, "${e.message} · tap here")
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

            val settings = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
            )
            rv.setTextViewText(R.id.updated, strip(ctx, snap, updating))
            rv.setOnClickPendingIntent(R.id.updated, settings)
            rv.setOnClickPendingIntent(R.id.refresh, broadcast(ctx, ACTION_REFRESH, 11))

            val tickers = cfg.tickers
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
                cell(R.id.change, q?.pct)
                val (d5, m1) = History.changes(ctx, t.symbol, q?.price)
                cell(R.id.change5d, d5)
                cell(R.id.change1m, m1)

                row.setTextViewText(R.id.session, q?.session ?: "")
                row.setViewVisibility(R.id.session, if (q?.session != null) View.VISIBLE else View.GONE)
                row.setViewVisibility(R.id.divider, if (i < tickers.size - 1) View.VISIBLE else View.INVISIBLE)
                row.setOnClickFillInIntent(R.id.row, Intent().putExtra(OpenQuoteActivity.EXTRA_SYMBOL, t.symbol))
                items.addItem(i.toLong(), row)
            }
            rv.setRemoteAdapter(R.id.list, items.build())
            return rv
        }

        private fun strip(ctx: Context, s: Store.Snapshot, updating: Boolean): String {
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

        /**
         * Accounting style: negatives in parentheses, positives unsigned with an invisible ")" so the % signs line up.
         * 2 decimals under 10%, 1 under 100%, none above, to fit the column.
         */
        private fun pctText(v: Double): CharSequence {
            val a = abs(v)
            val num = String.format(Locale.US, when { a < 9.995 -> "%.2f%%"; a < 99.95 -> "%.1f%%"; else -> "%.0f%%" }, a)
            if (v < 0 && num.any { it in '1'..'9' }) return "($num)"
            return android.text.SpannableString("$num)").apply {
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.TRANSPARENT), num.length, num.length + 1, 0)
            }
        }

        private fun price(p: Double): String {
            val digits = when {
                abs(p) >= 1.0 -> 2
                abs(p) >= 0.01 -> 4
                else -> 6
            }
            return NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }.format(p)
        }

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
