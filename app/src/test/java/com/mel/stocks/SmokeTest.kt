package com.mel.stocks

import android.app.Application
import android.appwidget.AppWidgetManager
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [35])
class SmokeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun settingsOpenEditAndLeave() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val f = MainActivity::class.java.getDeclaredField("tickers").apply { isAccessible = true }
        (f.get(a) as EditText).setText("AAPL\nMSFT")
        c.pause().stop().destroy()
        println("DIAG settings: " + Diag.last(app))
        assertEquals("AAPL\nMSFT", Config.load(app).tickersText)
    }

    @Test
    fun widgetRendersWithoutError() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.render(app)
        println("DIAG widget: " + Diag.last(app))
        assertNull(Diag.last(app))
    }

    @Test
    fun refreshEndToEnd() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.refresh(app)
        val s = Store.load(app)
        println("SNAPSHOT updatedAt=${s.updatedAt} failed=${s.failed} note=${s.note} rows=${s.rows}")
        println("DIAG refresh: " + Diag.last(app))
        assertNull(Diag.last(app))
    }

    @Test
    fun yahooHeaderVariants() {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MU?interval=1m&range=1d&includePrePost=true"
        val desk = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        val mob = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        val variants = listOf(
            "mobileUA+json" to mapOf("User-Agent" to mob, "Accept" to "application/json"),
            "desktopUA+json" to mapOf("User-Agent" to desk, "Accept" to "application/json"),
            "desktopUA+any" to mapOf("User-Agent" to desk, "Accept" to "*/*"),
            "curlUA+any" to mapOf("User-Agent" to "curl/8.5.0", "Accept" to "*/*"),
        )
        for ((name, h) in variants) {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            h.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = try { c.responseCode } catch (e: Exception) { -1 }
            println("VARIANT $name -> $code")
            c.disconnect()
        }
    }

    @Test
    fun yahooLive() {
        val cfg = Config.load(app)
        for (sym in listOf("MU", "SNDK", "GC=F")) {
            val r = Quotes.fetchOne(cfg, sym)
            println("YAHOO $sym -> price=${r.price} prev=${r.prev} pct=${r.pct} name=${r.name} session=${r.session} err=${r.error} raw=${r.raw?.take(300)}")
        }
    }
}
