package com.mel.stocks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [35])
class AlpacaTest {

    private fun snap(trade: String, daily: String) = JSONObject(
        """
        {"symbol":"MU",
         "latestTrade":{"t":"$trade","x":"V","p":101.5,"s":10},
         "minuteBar":{"t":"$trade","o":101,"h":102,"l":100,"c":101.4,"v":500},
         "dailyBar":{"t":"$daily","o":98,"h":100,"l":97,"c":100.0,"v":9000},
         "prevDailyBar":{"t":"2026-10-14T04:00:00Z","o":95,"h":96,"l":94,"c":95.0,"v":8000}}
        """.trimIndent()
    )

    @Test
    fun preMarketUsesLastSessionAsPreviousClose() {
        // 07:30 ET on Oct 16, before today's daily bar exists.
        val root = snap("2026-10-16T11:30:00.123456789Z", "2026-10-15T04:00:00Z")
        assertEquals(101.5, JsonPath.num(root, Presets.ALPACA.pricePath)!!, 1e-9)
        assertEquals(100.0, Alpaca.prev(root)!!, 1e-9)
        assertEquals("PRE", Alpaca.session(root))
    }

    @Test
    fun regularHoursUsesPrevDailyBar() {
        // 11:00 ET on Oct 16, today's bar present.
        val root = snap("2026-10-16T15:00:00Z", "2026-10-16T04:00:00Z")
        assertEquals(95.0, Alpaca.prev(root)!!, 1e-9)
        assertNull(Alpaca.session(root))
    }

    @Test
    fun afterHoursTag() {
        val root = snap("2026-10-16T21:15:00Z", "2026-10-16T04:00:00Z")
        assertEquals("AH", Alpaca.session(root))
    }

    @Test
    fun keyAndSecretGoIntoHeaders() {
        val p = Presets.ALPACA
        val c = Config(
            tickersText = "MU", showNames = true, preset = p.name, url = p.url, apiKey = "KID", headers = p.headers,
            pricePath = p.pricePath, prevPath = p.prevPath, pctPath = p.pctPath, namePath = p.namePath,
            timePath = p.timePath, regStartPath = p.regStartPath, regEndPath = p.regEndPath,
            tapUrl = "", intervalMin = 30, morning = "06:55", apiSecret = "SEC",
        )
        assertEquals("https://data.alpaca.markets/v2/stocks/MU/snapshot?feed=iex", c.requestUrl("MU"))
        assertEquals(mapOf("APCA-API-KEY-ID" to "KID", "APCA-API-SECRET-KEY" to "SEC"), c.headerMap("MU"))
    }
}
