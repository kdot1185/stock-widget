package com.mel.stocks

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuthNeeded(msg: String) : Exception(msg)

/**
 * E*TRADE market data over OAuth 1.0a.
 * Sign-in is out-of-band: we get a request token, you approve in the browser and copy the code back,
 * we trade it for an access token. E*TRADE kills that token at midnight ET, so this repeats daily.
 * A token idle for 2 hours goes inactive; we renew it automatically on a 401.
 */
object Etrade {
    const val PRESET = "E*TRADE (key + secret)"

    private const val API = "https://api.etrade.com"
    private const val AUTHORIZE = "https://us.etrade.com/e/t/etws/authorize"
    private const val PREFS = "etrade"
    private val ET: ZoneId = ZoneId.of("America/New_York")

    data class State(
        val key: String,
        val secret: String,
        val token: String,
        val tokenSecret: String,
        val tokenAt: Long,
        val reqToken: String,
        val reqSecret: String,
    ) {
        /** Access tokens die at midnight Eastern regardless of use. */
        val tokenLive: Boolean
            get() = token.isNotEmpty() && tokenAt >= ZonedDateTime.now(ET).toLocalDate().atStartOfDay(ET).toInstant().toEpochMilli()
    }

    fun load(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            p.getString("key", "")!!, p.getString("secret", "")!!,
            p.getString("token", "")!!, p.getString("tokenSecret", "")!!, p.getLong("tokenAt", 0L),
            p.getString("reqToken", "")!!, p.getString("reqSecret", "")!!,
        )
    }

    fun saveKeys(ctx: Context, key: String, secret: String) {
        val old = load(ctx)
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", key.trim()).putString("secret", secret.trim())
        // New keys invalidate any token issued under the old ones.
        if (old.key != key.trim() || old.secret != secret.trim()) e.remove("token").remove("tokenSecret").remove("tokenAt")
        e.apply()
    }

    fun status(ctx: Context): String {
        val s = load(ctx)
        return when {
            s.key.isEmpty() || s.secret.isEmpty() -> "Enter your consumer key and secret, then sign in."
            s.tokenLive -> "Signed in. E*TRADE ends the session at midnight ET."
            s.token.isNotEmpty() -> "Session expired at midnight ET. Sign in again."
            else -> "Not signed in."
        }
    }

    /** Step 1: returns the URL to open in the browser. */
    fun startSignIn(ctx: Context): String {
        val s = load(ctx)
        if (s.key.isEmpty() || s.secret.isEmpty()) throw IOException("Consumer key and secret are required")
        val body = call(s, "$API/oauth/request_token", "", "", mapOf("oauth_callback" to "oob"))
        val m = form(body)
        val t = m["oauth_token"] ?: throw IOException("No request token in response: ${body.take(160)}")
        val ts = m["oauth_token_secret"] ?: ""
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("reqToken", t).putString("reqSecret", ts).apply()
        return "$AUTHORIZE?key=${enc(s.key)}&token=${enc(t)}"
    }

    /** Step 2: trade the code shown by E*TRADE for an access token. */
    fun finishSignIn(ctx: Context, verifier: String) {
        val s = load(ctx)
        if (s.reqToken.isEmpty()) throw IOException("Tap Sign in first")
        val body = call(s, "$API/oauth/access_token", s.reqToken, s.reqSecret, mapOf("oauth_verifier" to verifier.trim()))
        val m = form(body)
        val t = m["oauth_token"] ?: throw IOException("No access token in response: ${body.take(160)}")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", t)
            .putString("tokenSecret", m["oauth_token_secret"] ?: "")
            .putLong("tokenAt", System.currentTimeMillis())
            .remove("reqToken").remove("reqSecret")
            .apply()
    }

    private fun renew(s: State): Boolean = try {
        call(s, "$API/oauth/renew_access_token", s.token, s.tokenSecret, emptyMap()); true
    } catch (e: Exception) { false }

    /** Quotes for all symbols; throws AuthNeeded when only a fresh sign-in will help. */
    fun fetchAll(ctx: Context, symbols: List<String>): List<Quotes.Result> {
        val s = load(ctx)
        if (s.key.isEmpty() || s.secret.isEmpty()) throw AuthNeeded("E*TRADE keys missing")
        if (!s.tokenLive) throw AuthNeeded("E*TRADE sign-in needed")
        val out = mutableListOf<Quotes.Result>()
        for (batch in symbols.chunked(25)) {
            val body = try {
                quoteCall(s, batch)
            } catch (e: HttpError) {
                if (e.code == 400 && batch.size > 1) {
                    // One bad symbol can fail the whole batch: retry individually so the good ones still land.
                    batch.forEach { sym ->
                        out += try { parse(quoteCall(s, listOf(sym)), listOf(sym)).first() }
                        catch (x: HttpError) { Quotes.Result(sym, error = if (x.code == 400) "Not found on E*TRADE" else x.message) }
                    }
                    continue
                }
                if (e.code == 400) { out += Quotes.Result(batch[0], error = "Not found on E*TRADE"); continue }
                throw e
            }
            out += parse(body, batch)
        }
        return out
    }

    private fun quoteCall(s: State, batch: List<String>): String {
        val url = "$API/v1/market/quote/${batch.joinToString(",") { pathSeg(it) }}.json?detailFlag=ALL"
        return try {
            call(s, url, s.token, s.tokenSecret, emptyMap())
        } catch (e: HttpError) {
            if (e.code != 401) throw e
            if (!renew(s)) throw AuthNeeded("E*TRADE sign-in needed")
            try { call(s, url, s.token, s.tokenSecret, emptyMap()) }
            catch (e2: HttpError) { if (e2.code == 401) throw AuthNeeded("E*TRADE sign-in needed") else throw e2 }
        }
    }

    private fun parse(body: String, asked: List<String>): List<Quotes.Result> {
        val root = JSONObject(body)
        val qr = obj(root, "QuoteResponse") ?: throw IOException("Unexpected response: ${body.take(160)}")
        val data = arr(qr, "QuoteData") ?: JSONArray()
        val got = mutableMapOf<String, Quotes.Result>()
        for (i in 0 until data.length()) {
            val q = data.optJSONObject(i) ?: continue
            val sym = obj(q, "Product")?.let { str(it, "symbol") }?.uppercase() ?: continue
            val all = obj(q, "All") ?: continue
            val last = num(all, "lastTrade")
            val prev = num(all, "previousClose")
            val name = str(all, "symbolDescription") ?: str(all, "companyName")
            val eh = obj(all, "ExtendedHourQuoteDetail") ?: obj(all, "ehQuote")
            val ehPrice = eh?.let { num(it, "lastPrice") }?.takeIf { it > 0 }
            val ehStatus = eh?.let { str(it, "quoteStatus") } ?: ""
            got[sym] = if (ehPrice != null) {
                if (ehStatus == "EH_BEFORE_OPEN") {
                    // Pre-market: yesterday's close is the reference, and it's what lastTrade still holds.
                    val base = last ?: prev
                    Quotes.Result(sym, name, ehPrice, base, base?.takeIf { it != 0.0 }?.let { (ehPrice / it - 1) * 100 }
                        ?: eh?.let { num(it, "percentChange") }, "PRE", raw = null)
                } else {
                    // After hours: show the whole day's move, Yahoo-style.
                    Quotes.Result(sym, name, ehPrice, prev, prev?.takeIf { it != 0.0 }?.let { (ehPrice / it - 1) * 100 }, "AH", raw = null)
                }
            } else {
                val pct = num(all, "changeClosePercentage")
                    ?: if (last != null && prev != null && prev != 0.0) (last / prev - 1) * 100 else null
                if (last == null) Quotes.Result(sym, error = "No price") else Quotes.Result(sym, name, last, prev, pct, null)
            }
        }
        return asked.map { a -> got[a.uppercase()]?.copy(raw = body) ?: Quotes.Result(a, error = "Not found on E*TRADE", raw = body) }
    }

    // --- OAuth 1.0a ---

    class HttpError(val code: Int, msg: String) : IOException(msg)

    private val rng = SecureRandom()

    private fun call(s: State, url: String, token: String, tokenSecret: String, extra: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Authorization", header("GET", url, s.key, s.secret, token, tokenSecret, extra))
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, "HTTP $code" + body.trim().take(160).let { if (it.isEmpty()) "" else ": $it" })
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun header(
        method: String, url: String, key: String, secret: String,
        token: String, tokenSecret: String, extra: Map<String, String>,
    ): String {
        val u = URI(url)
        val base = "${u.scheme.lowercase()}://${u.host.lowercase()}${u.rawPath}"
        val nonce = ByteArray(16).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        val oauth = sortedMapOf(
            "oauth_consumer_key" to key,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        if (token.isNotEmpty()) oauth["oauth_token"] = token
        oauth.putAll(extra)

        val query = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map {
            val i = it.indexOf('=')
            if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }
        val params = (oauth.entries.map { it.key to it.value } + query)
            .map { enc(it.first) to enc(it.second) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        // The path is signed exactly as sent, decoded first so it isn't double-encoded.
        val baseString = "$method&${enc(dec(base))}&${enc(params)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("${enc(secret)}&${enc(tokenSecret)}".toByteArray(), "HmacSHA1"))
        val sig = Base64.encodeToString(mac.doFinal(baseString.toByteArray()), Base64.NO_WRAP)

        oauth["oauth_signature"] = sig
        return "OAuth realm=\"\"," + oauth.entries.joinToString(",") { "${enc(it.key)}=\"${enc(it.value)}\"" }
    }

    // --- helpers ---

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun pathSeg(sym: String) = enc(sym.trim().uppercase())

    private fun form(body: String) = body.trim().split('&').mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1))
    }.toMap()

    // E*TRADE's JSON key casing isn't consistent across docs and responses; match keys case-insensitively.
    private fun key(o: JSONObject, name: String): String? = o.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }
    private fun obj(o: JSONObject, name: String): JSONObject? = key(o, name)?.let { o.optJSONObject(it) }
    private fun arr(o: JSONObject, name: String): JSONArray? = key(o, name)?.let { k ->
        o.optJSONArray(k) ?: o.optJSONObject(k)?.let { JSONArray().put(it) }
    }
    private fun str(o: JSONObject, name: String): String? = key(o, name)?.let { o.optString(it) }?.trim()?.takeIf { it.isNotEmpty() }
    private fun num(o: JSONObject, name: String): Double? = key(o, name)?.let { JsonPath.toNum(o.opt(it)) }
}
