package com.mel.stocks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Three years of daily closes, dividend-adjusted closes and dividends per symbol, from Yahoo's chart endpoint
 * (E*TRADE's API has no price history), fetched at most once per symbol per day. Every return column is a
 * total return: the live price from whatever quote source is selected, plus dividends, against a past close.
 */
object History {
    private const val PREFS = "history"
    private const val VERSION = 2
    private val ET: ZoneId = ZoneId.of("America/New_York")
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** One completed session. `adj` is Yahoo's split- and dividend-adjusted close (dividends reinvested on the ex-date). */
    data class Day(val date: LocalDate, val close: Double, val adj: Double)

    data class Closes(
        val fetchedOn: String,
        val days: List<Day>,
        val divs: Map<LocalDate, Double>,  // ex-date -> cash amount per share
        val name: String?,
    )

    /** Return windows. Months are calendar months back from the current session; 5D is five sessions back. */
    enum class Span(val label: String, val months: Long) {
        D1("1D", 0), D5("5D", 0), M1("1M", 1), M3("3M", 3), M6("6M", 6), Y1("1Y", 12), Y3("3Y", 36);

        companion object {
            fun of(label: String): Span? = values().firstOrNull { it.label == label }
        }
    }

    fun today(): LocalDate = LocalDate.now(ET)

    private val mem = ConcurrentHashMap<String, Pair<String, Closes>>()

    /** Fetch history for any symbol not already fetched today. Failures are recorded per symbol and retried next refresh. */
    fun refresh(ctx: Context, symbols: List<String>): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todayStr = today().toString()
        var firstErr: String? = null
        for (sym in symbols) {
            val cached = load(ctx, sym)
            if (cached != null && cached.fetchedOn == todayStr && cached.days.isNotEmpty()) continue
            try {
                val c = fetch(sym).copy(fetchedOn = todayStr)
                p.edit().putString(sym, encode(c)).apply()
            } catch (e: Exception) {
                if (firstErr == null) firstErr = "history ${sym}: ${e.message}"
            }
        }
        return firstErr
    }

    /** Stored history, or null if missing or in an older format (which the next refresh replaces). */
    fun load(ctx: Context, sym: String): Closes? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sym, null) ?: return null
        mem[sym]?.let { (raw, c) -> if (raw == s) return c }
        val c = decode(s) ?: return null
        mem[sym] = s to c
        return c
    }

    /**
     * Total return in percent over `span`, for a live `price`. 1D works off the quote source's own % change
     * (its previous close), adding back any dividend that goes ex today; longer spans use the stored history.
     */
    fun totalReturn(ctx: Context, sym: String, span: Span, price: Double?, pct1d: Double?): Double? {
        if (price == null) return null
        val c = load(ctx, sym)
        if (span == Span.D1) {
            if (pct1d == null) return null
            val div = c?.let { it.divs[sessionOf(it, today())] } ?: 0.0
            if (div == 0.0) return pct1d
            val prev = price / (1.0 + pct1d / 100.0)
            return if (prev > 0) ((price + div) / prev - 1.0) * 100.0 else pct1d
        }
        return c?.let { totalReturn(it, span, price, today()) }
    }

    /** The session a live price belongs to: today on weekdays, otherwise the last session on record. */
    private fun sessionOf(c: Closes, today: LocalDate): LocalDate =
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) c.days.lastOrNull()?.date ?: today
        else today

    /**
     * Pure math, testable without Android. Growth from the base close to the last completed close comes from the
     * adjusted closes (dividends reinvested on their ex-dates); the last leg is the live price plus any dividend
     * that goes ex in the current session, over the last completed close.
     */
    internal fun totalReturn(c: Closes, span: Span, price: Double, today: LocalDate): Double? {
        if (c.days.isEmpty() || price <= 0) return null
        val session = sessionOf(c, today)
        val done = c.days.filter { it.date < session }
        val last = done.lastOrNull() ?: return null
        val base = when (span) {
            Span.D1 -> last
            Span.D5 -> if (done.size >= 5) done[done.size - 5] else null
            else -> {
                val target = session.minusMonths(span.months)
                // The window must actually start inside the stored history, not just near its first day.
                if (done.first().date > target) null else done.lastOrNull { it.date <= target }
            }
        } ?: return null
        if (base.adj <= 0 || last.close <= 0) return null
        val div = c.divs[session] ?: 0.0
        val growth = last.adj / base.adj
        val tail = (price + div) / last.close
        return (growth * tail - 1.0) * 100.0
    }

    /** Store a history directly (tests, and anything that already has the data). */
    internal fun save(ctx: Context, sym: String, c: Closes) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(sym, encode(c)).apply()
    }

    private fun encode(c: Closes): String {
        val d = JSONArray()
        // [epochDay, close, adj]; adj omitted when it equals close.
        c.days.forEach { x ->
            val a = JSONArray().put(x.date.toEpochDay()).put(round(x.close))
            if (x.adj != x.close) a.put(round(x.adj))
            d.put(a)
        }
        val v = JSONArray()
        c.divs.entries.sortedBy { it.key.toEpochDay() }.forEach { (k, amt) -> v.put(JSONArray().put(k.toEpochDay()).put(amt)) }
        return JSONObject().put("v", VERSION).put("on", c.fetchedOn).put("d", d).put("div", v)
            .apply { c.name?.let { put("n", it) } }.toString()
    }

    private fun decode(s: String): Closes? = try {
        val o = JSONObject(s)
        if (o.optInt("v") != VERSION) null else {
            val arr = o.getJSONArray("d")
            val days = (0 until arr.length()).map {
                val e = arr.getJSONArray(it)
                val close = e.getDouble(1)
                Day(LocalDate.ofEpochDay(e.getLong(0)), close, if (e.length() > 2) e.getDouble(2) else close)
            }
            val dv = o.optJSONArray("div") ?: JSONArray()
            val divs = (0 until dv.length()).associate {
                val e = dv.getJSONArray(it)
                LocalDate.ofEpochDay(e.getLong(0)) to e.getDouble(1)
            }
            Closes(o.getString("on"), days, divs, o.optString("n").ifEmpty { null })
        }
    } catch (e: Exception) { null }

    private fun round(x: Double) = Math.round(x * 1e6) / 1e6

    internal fun fetch(sym: String): Closes {
        val enc = URLEncoder.encode(sym, "UTF-8")
        val end = Instant.now().epochSecond + 86_400
        // Three years plus a margin, so the 3Y base close exists even after a long weekend.
        val start = today().minusYears(3).minusDays(20).atStartOfDay(ET).toEpochSecond()
        var last: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val body = get(
                    "https://$host.finance.yahoo.com/v8/finance/chart/$enc" +
                        "?period1=$start&period2=$end&interval=1d&events=div&includeAdjustedClose=true",
                )
                return parse(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("unavailable")
    }

    internal fun parse(body: String): Closes {
        val r = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.getJSONArray("timestamp")
        val ind = r.getJSONObject("indicators")
        val q = ind.getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val adj = ind.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
        val out = mutableListOf<Day>()
        for (i in 0 until minOf(ts.length(), q.length())) {
            if (q.isNull(i)) continue
            val d = Instant.ofEpochSecond(ts.getLong(i)).atZone(ET).toLocalDate()
            val close = q.getDouble(i)
            val a = if (adj != null && i < adj.length() && !adj.isNull(i)) adj.getDouble(i).takeIf { it > 0 } else null
            out += Day(d, close, a ?: close)
        }
        if (out.isEmpty()) throw IOException("no closes")
        val divs = mutableMapOf<LocalDate, Double>()
        r.optJSONObject("events")?.optJSONObject("dividends")?.let { ev ->
            for (k in ev.keys()) {
                val e = ev.optJSONObject(k) ?: continue
                val amt = e.optDouble("amount", Double.NaN)
                val at = if (e.has("date")) e.getLong("date") else k.toLongOrNull() ?: continue
                if (!amt.isNaN() && amt > 0) {
                    val d = Instant.ofEpochSecond(at).atZone(ET).toLocalDate()
                    divs[d] = (divs[d] ?: 0.0) + amt
                }
            }
        }
        val meta = r.optJSONObject("meta")
        val name = meta?.optString("longName")?.ifBlank { null } ?: meta?.optString("shortName")?.ifBlank { null }
        val days = out.groupBy { it.date }.map { it.value.last() }.sortedBy { it.date }
        return Closes("", days, divs, name)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val body = st?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }
}
