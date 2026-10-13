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
        Scheduler.ensure(context)
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
                Scheduler.scheduleMorning(context)
                refreshAsync(context, goAsync())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Scheduler.ensure(context)
                render(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.mel.stocks.REFRESH"
        const val ACTION_MORNING = "com.mel.stocks.MORNING"

        private val busy = AtomicBoolean(false)

        private const val INK = 0xFF1D1D1F.toInt()
        private const val MUTED = 0xFF86868B.toInt()
        private const val UP = 0xFF0B8A47.toInt()
        private const val DOWN = 0xFFE0281F.toInt()

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
                Store.record(ctx, Quotes.fetchAll(ctx, Config.load(ctx)))
            } catch (e: AuthNeeded) {
                Store.note(ctx, "${e.message} · tap here")
            } catch (e: Exception) {
                // Keep the last good snapshot; the strip will show the old time.
            } finally {
                busy.set(false)
                render(ctx)
            }
        }

        fun render(ctx: Context, updating: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StockWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, build(ctx, updating))
        }

        private fun build(ctx: Context, updating: Boolean): RemoteViews {
            val cfg = Config.load(ctx)
            val snap = Store.load(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)

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
                val name = t.label ?: q?.name ?: ""
                row.setTextViewText(R.id.name, name)
                row.setViewVisibility(R.id.name, if (cfg.showNames && name.isNotEmpty()) View.VISIBLE else View.GONE)

                row.setTextViewText(R.id.price, q?.price?.let(::price) ?: "—")
                row.setTextColor(R.id.price, if (q == null || q.stale) MUTED else INK)

                val pct = q?.pct
                row.setTextViewText(R.id.change, pct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "")
                row.setTextColor(R.id.change, when {
                    pct == null || q == null || q.stale -> MUTED
                    pct > 0.0049 -> UP
                    pct < -0.0049 -> DOWN
                    else -> MUTED
                })

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
            if (s.updatedAt == 0L) return if (s.attemptAt > 0) "Refresh failed · tap ⟳ to retry" else "Not updated yet"
            val t = stamp(ctx, s.updatedAt)
            return when {
                s.failed > 0 && s.attemptAt > s.updatedAt -> "Refresh failed · last $t"
                s.failed > 0 -> "Updated $t · ${s.failed} failed"
                else -> "Updated $t"
            }
        }

        private fun stamp(ctx: Context, millis: Long): String {
            val time = DateFormat.getTimeFormat(ctx).format(Date(millis))
            val then = Calendar.getInstance().apply { timeInMillis = millis }
            val now = Calendar.getInstance()
            val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            return if (sameDay) time else SimpleDateFormat("MMM d", Locale.US).format(Date(millis)) + ", " + time
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
