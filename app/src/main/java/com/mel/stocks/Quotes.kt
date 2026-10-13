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

    fun fetchAll(cfg: Config): List<Result> {
        val symbols = cfg.tickers.map { it.symbol }.distinct()
        if (symbols.isEmpty()) return emptyList()
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
            body = get(url, cfg.headerMap(symbol))
            val root = try { JSONTokener(body).nextValue() } catch (e: JSONException) { null }
            if (root !is JSONObject && root !is JSONArray) return Result(symbol, error = "Response isn't JSON", raw = body)
            val price = JsonPath.num(root, cfg.pricePath)
                ?: return Result(symbol, error = "Price not found at \"${cfg.pricePath}\"", raw = body)
            val prev = cfg.prevPath.ifBlank { null }?.let { JsonPath.num(root, it) }
            val pct = cfg.pctPath.ifBlank { null }?.let { JsonPath.num(root, it) }
                ?: prev?.takeIf { it != 0.0 }?.let { (price / it - 1.0) * 100.0 }
            val name = cfg.namePath.ifBlank { null }?.let { JsonPath.str(root, it) }
            Result(symbol, name, price, prev, pct, session(root, cfg), raw = body)
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

    private fun get(url: String, headers: Map<String, String>): String {
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
        return Snapshot(rows, p.getLong("updatedAt", 0L), p.getLong("attemptAt", 0L), p.getInt("failed", 0))
    }

    fun record(ctx: Context, results: List<Quotes.Result>) {
        val old = load(ctx)
        val now = System.currentTimeMillis()
        val rows = old.rows.toMutableMap()
        var ok = 0
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
            .apply()
    }
}
