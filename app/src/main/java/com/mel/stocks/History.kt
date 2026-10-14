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

/**
 * Daily closes for the 5D / 1M columns. E*TRADE's API has no price history, so closes come from
 * Yahoo's chart endpoint, fetched at most once per symbol per day. The live price (from whatever
 * quote source is selected) is compared against those closes at render time.
 */
object History {
    private const val PREFS = "history"
    private val ET: ZoneId = ZoneId.of("America/New_York")
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    data class Closes(val fetchedOn: String, val days: List<Pair<LocalDate, Double>>)

    fun today(): LocalDate = LocalDate.now(ET)

    /** Fetch closes for any symbol not already fetched today. Failures are recorded per symbol and retried next refresh. */
    fun refresh(ctx: Context, symbols: List<String>): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todayStr = today().toString()
        var firstErr: String? = null
        for (sym in symbols) {
            val cached = load(ctx, sym)
            if (cached != null && cached.fetchedOn == todayStr && cached.days.isNotEmpty()) continue
            try {
                val days = fetch(sym)
                val arr = JSONArray()
                days.forEach { (d, c) -> arr.put(JSONArray().put(d.toString()).put(c)) }
                p.edit().putString(sym, JSONObject().put("on", todayStr).put("d", arr).toString()).apply()
            } catch (e: Exception) {
                if (firstErr == null) firstErr = "history ${sym}: ${e.message}"
            }
        }
        return firstErr
    }

    fun load(ctx: Context, sym: String): Closes? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sym, null)?.let { s ->
            val o = JSONObject(s)
            val arr = o.getJSONArray("d")
            Closes(o.getString("on"), (0 until arr.length()).map {
                val e = arr.getJSONArray(it)
                LocalDate.parse(e.getString(0)) to e.getDouble(1)
            })
        }
    } catch (e: Exception) { null }

    /** (∆5D %, ∆1M %) of `price` against completed sessions before the current one; null where history is missing. */
    fun changes(ctx: Context, sym: String, price: Double?): Pair<Double?, Double?> {
        if (price == null) return null to null
        val days = load(ctx, sym)?.days ?: return null to null
        if (days.isEmpty()) return null to null
        // The session the live price belongs to: today on weekdays, otherwise the last session on record.
        val t = today()
        val session = if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) days.last().first else t
        val done = days.filter { it.first < session }
        fun pct(base: Double?) = base?.takeIf { it > 0 }?.let { (price / it - 1.0) * 100.0 }
        val d5 = if (done.size >= 5) done[done.size - 5].second else null
        val target = session.minusMonths(1)
        val m1 = done.lastOrNull { it.first <= target }?.second
        return pct(d5) to pct(m1)
    }

    private fun fetch(sym: String): List<Pair<LocalDate, Double>> {
        val enc = URLEncoder.encode(sym, "UTF-8")
        var last: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val body = get("https://$host.finance.yahoo.com/v8/finance/chart/$enc?range=3mo&interval=1d")
                val r = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val ts = r.getJSONArray("timestamp")
                val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
                val out = mutableListOf<Pair<LocalDate, Double>>()
                for (i in 0 until minOf(ts.length(), q.length())) {
                    if (q.isNull(i)) continue
                    val d = Instant.ofEpochSecond(ts.getLong(i)).atZone(ET).toLocalDate()
                    out += d to q.getDouble(i)
                }
                if (out.isEmpty()) throw IOException("no closes")
                return out.groupBy { it.first }.map { it.value.last() }.sortedBy { it.first }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("unavailable")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7_000
            c.readTimeout = 10_000
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
