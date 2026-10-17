package com.mel.stocks

import android.content.Context
import java.net.URLEncoder

data class Ticker(val symbol: String, val label: String?)

/**
 * Everything about where quotes come from lives here, so the widget never knows which API it's talking to.
 * A source is: a URL template ({symbol}, {key}), optional headers, and paths into the JSON response.
 */
data class Config(
    val tickersText: String,
    val showNames: Boolean,
    val preset: String,
    val url: String,
    val apiKey: String,
    val headers: String,
    val pricePath: String,
    val prevPath: String,
    val pctPath: String,
    val namePath: String,
    val timePath: String,
    val regStartPath: String,
    val regEndPath: String,
    val tapUrl: String,
    val intervalMin: Int,
    val morning: String,
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val opacity: Int = 100,
    val textMode: String = "Auto",
    val apiSecret: String = "",
) {
    /** One ticker per line (commas also work). Anything after the symbol is a display label: "GC=F Gold". */
    val tickers: List<Ticker>
        get() = tickersText.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                Ticker(parts[0].uppercase(), parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
            }

    fun requestUrl(symbol: String) = expand(url, symbol, encode = true)

    fun tapUrlFor(symbol: String) = expand(tapUrl, symbol, encode = true)

    fun headerMap(symbol: String): Map<String, String> = headers.lines()
        .mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to expand(line.substring(i + 1).trim(), symbol, encode = false)
        }
        .filter { it.first.isNotEmpty() }
        .toMap()

    private fun expand(template: String, symbol: String, encode: Boolean): String {
        fun enc(s: String) = if (encode) URLEncoder.encode(s, "UTF-8").replace("%3D", "=").replace("+", "%20") else s
        return template.trim()
            .replace("{symbol}", enc(symbol))
            .replace("{SYMBOL}", enc(symbol))
            .replace("{key}", enc(apiKey.trim()))
            .replace("{KEY}", enc(apiKey.trim()))
            .replace("{secret}", enc(apiSecret.trim()))
            .replace("{SECRET}", enc(apiSecret.trim()))
    }

    companion object {
        private const val PREFS = "config"

        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Presets.YAHOO
            return Config(
                tickersText = p.getString("tickers", "SNDK\nGC=F\nMU")!!,
                showNames = p.getBoolean("showNames", true),
                preset = p.getString("preset", d.name)!!,
                url = p.getString("url", d.url)!!,
                apiKey = p.getString("apiKey", "")!!,
                headers = p.getString("headers", d.headers)!!,
                pricePath = p.getString("pricePath", d.pricePath)!!,
                prevPath = p.getString("prevPath", d.prevPath)!!,
                pctPath = p.getString("pctPath", d.pctPath)!!,
                namePath = p.getString("namePath", d.namePath)!!,
                timePath = p.getString("timePath", d.timePath)!!,
                regStartPath = p.getString("regStartPath", d.regStartPath)!!,
                regEndPath = p.getString("regEndPath", d.regEndPath)!!,
                tapUrl = p.getString("tapUrl", "https://finance.yahoo.com/quote/{symbol}")!!,
                intervalMin = p.getInt("intervalMin", 30),
                morning = p.getString("morning", "06:55")!!,
                bgColor = p.getInt("bgColor", 0xFFFFFFFF.toInt()),
                opacity = p.getInt("opacity", 100),
                textMode = p.getString("textMode", "Auto")!!,
                apiSecret = p.getString("apiSecret", "")!!,
            )
        }

        fun save(ctx: Context, c: Config) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("tickers", c.tickersText)
                .putBoolean("showNames", c.showNames)
                .putString("preset", c.preset)
                .putString("url", c.url)
                .putString("apiKey", c.apiKey)
                .putString("headers", c.headers)
                .putString("pricePath", c.pricePath)
                .putString("prevPath", c.prevPath)
                .putString("pctPath", c.pctPath)
                .putString("namePath", c.namePath)
                .putString("timePath", c.timePath)
                .putString("regStartPath", c.regStartPath)
                .putString("regEndPath", c.regEndPath)
                .putString("tapUrl", c.tapUrl)
                .putInt("intervalMin", c.intervalMin)
                .putString("morning", c.morning)
                .putInt("bgColor", c.bgColor)
                .putInt("opacity", c.opacity)
                .putString("textMode", c.textMode)
                .putString("apiSecret", c.apiSecret)
                .apply()
        }

        /** "6:55", "06:55", "18:30" -> (h, m); anything else -> null (off). */
        fun parseTime(s: String): Pair<Int, Int>? {
            val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) h to min else null
        }
    }
}

data class Preset(
    val name: String,
    val url: String,
    val headers: String = "",
    val pricePath: String,
    val prevPath: String = "",
    val pctPath: String = "",
    val namePath: String = "",
    val timePath: String = "",
    val regStartPath: String = "",
    val regEndPath: String = "",
)

object Presets {
    /** Unofficial chart endpoint: no key, includes pre/post-market bars. */
    val YAHOO = Preset(
        name = "Yahoo Finance (no key)",
        url = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d&includePrePost=true",
        pricePath = "chart.result.0.indicators.quote.0.close.last | chart.result.0.meta.regularMarketPrice",
        prevPath = "chart.result.0.meta.chartPreviousClose | chart.result.0.meta.previousClose",
        namePath = "chart.result.0.meta.shortName | chart.result.0.meta.longName",
        timePath = "chart.result.0.timestamp.last | chart.result.0.meta.regularMarketTime",
        regStartPath = "chart.result.0.meta.currentTradingPeriod.regular.start",
        regEndPath = "chart.result.0.meta.currentTradingPeriod.regular.end",
    )

    /**
     * Alpaca market-data snapshot. Free accounts get the IEX feed (one exchange, ~2.5% of volume, trading
     * roughly 8:00-17:00 ET); a paid SIP subscription can switch the URL to feed=sip for every exchange.
     * Previous close and the PRE/AH tag are worked out in [Alpaca], since the snapshot's bars shift at the open.
     */
    val ALPACA = Preset(
        name = "Alpaca (key + secret)",
        url = "https://data.alpaca.markets/v2/stocks/{symbol}/snapshot?feed=iex",
        headers = "APCA-API-KEY-ID: {key}\nAPCA-API-SECRET-KEY: {secret}",
        pricePath = "latestTrade.p | minuteBar.c | dailyBar.c",
        prevPath = "prevDailyBar.c",
    )

    val ALL = listOf(
        YAHOO,
        ALPACA,
        Preset(
            name = "Finnhub (key)",
            url = "https://finnhub.io/api/v1/quote?symbol={symbol}&token={key}",
            pricePath = "c", prevPath = "pc", pctPath = "dp",
        ),
        Preset(
            name = "Twelve Data (key)",
            url = "https://api.twelvedata.com/quote?symbol={symbol}&apikey={key}",
            pricePath = "close", prevPath = "previous_close", pctPath = "percent_change", namePath = "name",
        ),
        Preset(
            name = "Financial Modeling Prep (key)",
            url = "https://financialmodelingprep.com/stable/quote?symbol={symbol}&apikey={key}",
            pricePath = "0.price", prevPath = "0.previousClose",
            pctPath = "0.changePercentage | 0.changesPercentage", namePath = "0.name",
        ),
        Preset(
            name = "Alpha Vantage (key)",
            url = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol={symbol}&apikey={key}",
            pricePath = "[\"Global Quote\"][\"05. price\"]",
            prevPath = "[\"Global Quote\"][\"08. previous close\"]",
            pctPath = "[\"Global Quote\"][\"10. change percent\"]",
        ),
    )

    const val CUSTOM = "Custom"

    val NAMES = ALL.map { it.name } + Etrade.PRESET + CUSTOM

    fun byName(n: String) = ALL.firstOrNull { it.name == n }
}
