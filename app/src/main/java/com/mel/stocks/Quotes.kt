package com.mel.stocks

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Quotes {

    data class Result(
        val symbol: String,
        val name: String? = null,
        val price: Double? = null,
        val prev: Double? = null,
        val pct: Double? = null,
        val session: String? = null,
        val error: String? = null,
        val raw: String? = null,
    )

    // Yahoo turns away requests that don't look like a browser.
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** Throws AuthNeeded when the source needs a fresh sign-in (E*TRADE). */
    fun fetchAll(ctx: Context, cfg: Config): List<Result> {
        val symbols = cfg.tickers.map { it.symbol }.distinct()
        if (symbols.isEmpty()) return emptyList()
        if (cfg.preset == Etrade.PRESET) return Etrade.fetchAll(ctx, symbols)
        val pool = Executors.newFixedThreadPool(minOf(6, symbols.size))
        return try {
            val futures = pool.invokeAll(symbols.map { s -> Callable { fetchOne(cfg, s) } }, 25, TimeUnit.SECONDS)
            futures.mapIndexed { i, f ->
                try { f.get() } catch (e: Exception) { Result(symbols[i], error = "Timed out") }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun fetchOne(cfg: Config, symbol: String): Result {
        var body: String? = null
        return try {
            val url = cfg.requestUrl(symbol)
            if (url.isBlank()) return Result(symbol, error = "No API URL set")
            body = if (Yahoo.handles(url)) Yahoo.get(url) { u -> get(u, cfg.headerMap(symbol)) } else get(url, cfg.headerMap(symbol))
            val root = try { JSONTokener(body).nextValue() } catch (e: JSONException) { null }
            if (root !is JSONObject && root !is JSONArray) return Result(symbol, error = "Response isn't JSON", raw = body)
            val price = JsonPath.num(root, cfg.pricePath)
                ?: return Result(symbol, error = "Price not found at \"${cfg.pricePath}\"", raw = body)
            val alpaca = Alpaca.handles(url)
            val prev = (if (alpaca) Alpaca.prev(root) else null)
                ?: cfg.prevPath.ifBlank { null }?.let { JsonPath.num(root, it) }
            val pct = cfg.pctPath.ifBlank { null }?.let { JsonPath.num(root, it) }
                ?: prev?.takeIf { it != 0.0 }?.let { (price / it - 1.0) * 100.0 }
            val name = cfg.namePath.ifBlank { null }?.let { JsonPath.str(root, it) }
            val sess = if (alpaca) Alpaca.session(root) else session(root, cfg)
            Result(symbol, name, price, prev, pct, sess, raw = body)
        } catch (e: Exception) {
            Result(symbol, error = e.message ?: e.javaClass.simpleName, raw = body)
        }
    }

    /** "PRE" before the regular session, "AH" after it; needs the three optional time paths. */
    private fun session(root: Any, cfg: Config): String? {
        if (cfg.timePath.isBlank() || cfg.regStartPath.isBlank() || cfg.regEndPath.isBlank()) return null
        fun sec(x: Double) = if (x > 1e11) x / 1000.0 else x
        val t = JsonPath.num(root, cfg.timePath)?.let(::sec) ?: return null
        val start = JsonPath.num(root, cfg.regStartPath)?.let(::sec) ?: return null
        val end = JsonPath.num(root, cfg.regEndPath)?.let(::sec) ?: return null
        return when {
            t < start && start - t <= 6 * 3600 -> "PRE"
            t > end && t - end <= 5 * 3600 -> "AH"
            else -> null
        }
    }

    internal fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code" + body.trim().take(140).let { if (it.isEmpty()) "" else ": $it" })
            return body
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Yahoo rate-limits (429) clients that arrive without its session cookie. Do what the website does:
 * pick up the cookie from fc.yahoo.com, fetch a crumb, send both. On 401/429, refresh the session and
 * retry once, on the other query host.
 */
object Yahoo {
    private val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
    @Volatile private var crumb: String? = null
    @Volatile private var crumbAt = 0L

    init {
        if (java.net.CookieHandler.getDefault() == null) java.net.CookieHandler.setDefault(cookies)
    }

    fun handles(url: String) = url.contains("finance.yahoo.com")

    fun get(url: String, fetch: (String) -> String): String {
        session(force = false)
        return try {
            fetch(withCrumb(url))
        } catch (e: IOException) {
            val m = e.message ?: ""
            if (!(m.startsWith("HTTP 429") || m.startsWith("HTTP 401") || m.startsWith("HTTP 403"))) throw e
            session(force = true)
            val other = if (url.contains("query1.")) url.replace("query1.", "query2.") else url.replace("query2.", "query1.")
            fetch(withCrumb(other))
        }
    }

    private fun withCrumb(url: String): String {
        val c = crumb ?: return url
        return url + (if (url.contains('?')) "&" else "?") + "crumb=" + java.net.URLEncoder.encode(c, "UTF-8")
    }

    @Synchronized
    private fun session(force: Boolean) {
        if (!force && crumb != null && System.currentTimeMillis() - crumbAt < 6 * 3600_000L) return
        try { raw("https://fc.yahoo.com/") } catch (e: Exception) { }
        crumb = try {
            raw("https://query2.finance.yahoo.com/v1/test/getcrumb").trim()
                .takeIf { it.isNotEmpty() && it.length < 40 && !it.contains('<') && !it.contains('{') }
        } catch (e: Exception) { null }
        crumbAt = System.currentTimeMillis()
    }

    /** GET that tolerates error statuses (fc.yahoo.com answers 404 but still sets the cookie). */
    private fun raw(url: String): String {
        val c = java.net.URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA_DESKTOP)
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

    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}

/**
 * Alpaca snapshots carry the latest trade plus two daily bars, but which bar is "yesterday" depends on the
 * hour: until today's bar exists, dailyBar is still the last session, so it is the previous close. The
 * snapshot has no session fields either, so PRE/AH comes from the trade's time in New York.
 */
object Alpaca {
    private val ET: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

    fun handles(url: String) = url.contains("data.alpaca.markets")

    private fun at(root: Any?, path: String): java.time.ZonedDateTime? =
        JsonPath.str(root, path)?.let {
            try { java.time.Instant.parse(it).atZone(ET) } catch (e: Exception) { null }
        }

    fun prev(root: Any?): Double? {
        val tradeDay = at(root, "latestTrade.t")?.toLocalDate()
        val barDay = at(root, "dailyBar.t")?.toLocalDate()
        return if (tradeDay != null && barDay != null && barDay.isBefore(tradeDay)) JsonPath.num(root, "dailyBar.c")
        else JsonPath.num(root, "prevDailyBar.c")
    }

    /** PRE for 4:00-9:30 ET, AH for 16:00-20:00 ET, by the time of the latest trade. */
    fun session(root: Any?): String? {
        val t = at(root, "latestTrade.t") ?: return null
        val m = t.hour * 60 + t.minute
        return when {
            m in 240 until 570 -> "PRE"
            m in 960 until 1200 -> "AH"
            else -> null
        }
    }
}

/** Last known quotes, kept so a failed refresh shows stale numbers instead of blanks. */
object Store {
    data class Row(
        val symbol: String,
        val name: String?,
        val price: Double?,
        val pct: Double?,
        val session: String?,
        val stale: Boolean,
    )

    data class Snapshot(
        val rows: Map<String, Row>,
        val updatedAt: Long,   // last refresh where at least one quote came back
        val attemptAt: Long,   // last refresh attempt
        val failed: Int,       // symbols that failed on the last attempt
        val note: String?,     // overrides the strip, e.g. "E*TRADE sign-in needed"
        val err: String? = null, // first error from the last attempt
    )

    private const val PREFS = "quotes"

    fun load(ctx: Context): Snapshot {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = mutableMapOf<String, Row>()
        try {
            val arr = JSONArray(p.getString("rows", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = Row(
                    symbol = o.getString("s"),
                    name = o.optString("n").ifEmpty { null },
                    price = if (o.has("p")) o.getDouble("p") else null,
                    pct = if (o.has("c")) o.getDouble("c") else null,
                    session = o.optString("x").ifEmpty { null },
                    stale = o.optBoolean("st"),
                )
                rows[r.symbol] = r
            }
        } catch (e: JSONException) { }
        return Snapshot(
            rows, p.getLong("updatedAt", 0L), p.getLong("attemptAt", 0L), p.getInt("failed", 0),
            p.getString("note", null),
            p.getString("err", null),
        )
    }

    fun record(ctx: Context, results: List<Quotes.Result>) {
        val old = load(ctx)
        val now = System.currentTimeMillis()
        val rows = old.rows.toMutableMap()
        var ok = 0
        val firstErr = results.firstOrNull { it.error != null }?.error
        for (r in results) {
            if (r.error == null && r.price != null) {
                ok++
                rows[r.symbol] = Row(r.symbol, r.name ?: old.rows[r.symbol]?.name, r.price, r.pct, r.session, stale = false)
            } else {
                old.rows[r.symbol]?.let { rows[r.symbol] = it.copy(stale = true) }
            }
        }
        val arr = JSONArray()
        for (r in rows.values) {
            val o = JSONObject().put("s", r.symbol).put("st", r.stale)
            r.name?.let { o.put("n", it) }
            r.price?.let { o.put("p", it) }
            r.pct?.let { o.put("c", it) }
            r.session?.let { o.put("x", it) }
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rows", arr.toString())
            .putLong("updatedAt", if (ok > 0) now else old.updatedAt)
            .putLong("attemptAt", now)
            .putInt("failed", results.size - ok)
            .putString("err", firstErr)
            .remove("note")
            .apply()
    }

    /** A refresh that couldn't run at all: keep the old numbers (greyed) and say why in the strip. */
    fun note(ctx: Context, msg: String) {
        val old = load(ctx)
        val arr = JSONArray()
        for (r in old.rows.values) {
            val o = JSONObject().put("s", r.symbol).put("st", true)
            r.name?.let { o.put("n", it) }
            r.price?.let { o.put("p", it) }
            r.pct?.let { o.put("c", it) }
            r.session?.let { o.put("x", it) }
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rows", arr.toString())
            .putLong("attemptAt", System.currentTimeMillis())
            .putString("note", msg)
            .apply()
    }
}
