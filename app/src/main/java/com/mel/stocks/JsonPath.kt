package com.mel.stocks

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal path language for pulling one value out of any quote API's JSON.
 *   a.b.0.c              objects by key, arrays by index (negative counts from the end)
 *   ["Global Quote"]["05. price"]   bracketed keys for names with spaces or dots
 *   close.last / .first  last or first non-null element of an array
 *   path1 | path2        fallbacks: first one that resolves wins
 */
object JsonPath {

    fun get(root: Any?, expr: String): Any? {
        for (alt in expr.split('|')) {
            val p = alt.trim()
            if (p.isEmpty()) continue
            val v = try { one(root, p) } catch (e: IllegalArgumentException) { null }
            if (v != null && v != JSONObject.NULL) return v
        }
        return null
    }

    fun num(root: Any?, expr: String): Double? = toNum(get(root, expr))

    fun str(root: Any?, expr: String): String? = get(root, expr)?.let {
        when (it) {
            is JSONObject, is JSONArray -> null
            else -> it.toString().trim().takeIf { s -> s.isNotEmpty() }
        }
    }

    fun toNum(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.replace(Regex("[%,$+\\s]"), "").toDoubleOrNull()
        else -> null
    }

    private fun one(root: Any?, path: String): Any? {
        var cur: Any? = root
        for (t in tokens(path)) {
            cur = when (cur) {
                is JSONObject -> if (cur.has(t)) cur.get(t) else return null
                is JSONArray -> when (t) {
                    "last" -> (cur.length() - 1 downTo 0).map { cur.opt(it) }.firstOrNull { it != null && it != JSONObject.NULL }
                    "first" -> (0 until cur.length()).map { cur.opt(it) }.firstOrNull { it != null && it != JSONObject.NULL }
                    else -> {
                        val i = t.toIntOrNull() ?: return null
                        val idx = if (i < 0) cur.length() + i else i
                        if (idx in 0 until cur.length()) cur.opt(idx) else null
                    }
                }
                else -> return null
            }
            if (cur == null || cur == JSONObject.NULL) return null
        }
        return cur
    }

    private fun tokens(path: String): List<String> {
        var p = path.trim()
        if (p.startsWith("$")) p = p.substring(1)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '.' -> { flush(); i++ }
                '[' -> {
                    flush()
                    val close = p.indexOf(']', i)
                    val q = p.getOrNull(i + 1)
                    if (q == '"' || q == '\'') {
                        val end = p.indexOf(q, i + 2)
                        require(end > 0) { "unclosed quote" }
                        out += p.substring(i + 2, end)
                        i = p.indexOf(']', end).let { require(it > 0) { "unclosed bracket" }; it + 1 }
                    } else {
                        require(close > 0) { "unclosed bracket" }
                        out += p.substring(i + 1, close).trim()
                        i = close + 1
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
