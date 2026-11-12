package com.mel.stocks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import org.robolectric.annotation.Config as RConfig

/** Renders the list screen to PNGs (CI uploads them as a build artifact) so the layout can be checked by eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun seed(appBg: Int = 0) {
        Config.save(app, Config.load(app).copy(
            tickersText = "SNDK\nWDC\nMU\nMETA\nBITX\nKBR\nLRCX\nSPY\nBTC-USD\nNVDA\nGOOG\nOBDC",
            appBgColor = appBg,
        ))
        // symbol, price, 1D %, daily drift (sets the longer returns), name
        val rows = listOf(
            listOf("SNDK", 859.99, -5.1, 0.0029, "Sandisk Corporation"),
            listOf("WDC", 118.40, -4.2, 0.0028, "Western Digital Corporation"),
            listOf("MU", 725.11, -3.2, 0.0031, "Micron Technology, Inc."),
            listOf("META", 641.39, 1.1, 0.0009, "Meta Platforms, Inc."),
            listOf("BITX", 21.70, -6.9, -0.0039, "2x Bitcoin Strategy ETF"),
            listOf("KBR", 44.10, -0.6, -0.0006, "KBR, Inc."),
            listOf("LRCX", 141.02, -3.3, 0.0017, "Lam Research Corporation"),
            listOf("SPY", 672.04, -0.9, 0.0008, "SPDR S&P 500 ETF Trust"),
            listOf("BTC-USD", 88412.0, -2.0, 0.0010, "Bitcoin USD"),
            listOf("NVDA", 176.32, 4.46, 0.0016, "NVIDIA Corporation"),
            listOf("GOOG", 288.51, -1.2, 0.0010, "Alphabet Inc."),
            listOf("OBDC", 13.21, 0.3, 0.0001, "Blue Owl Capital Corporation"),
        )
        Store.record(app, rows.map { r ->
            val p = r[1] as Double; val c = r[2] as Double
            Quotes.Result(r[0] as String, null, p, p / (1 + c / 100), c)
        })
        val today = History.today()
        for (r in rows) {
            val sym = r[0] as String; val price = r[1] as Double; val drift = r[3] as Double
            val span = if (sym == "SNDK") 430L else 1130L
            val dates = (1L..span).map { today.minusDays(it) }.filter { it.dayOfWeek.value <= 5 }.sortedBy { it.toEpochDay() }
            val n = dates.size
            val days = dates.mapIndexed { k, d ->
                val close = price / Math.exp(drift * (n - k) + 0.04 * Math.sin(k / 9.0))
                History.Day(d, close, close * (1 - 0.00008 * (n - k)))
            }
            History.save(app, sym, History.Closes(today.toString(), days, emptyMap(), r[4] as String))
        }
    }

    private fun shoot(name: String, appBg: Int = 0, scrollRight: Boolean = false) {
        seed(appBg)
        val c = Robolectric.buildActivity(WatchlistActivity::class.java).setup()
        val root: View = c.get().window.decorView
        val dm = c.get().resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        if (scrollRight) {
            val f = WatchlistActivity::class.java.getDeclaredField("bodyScroll").apply { isAccessible = true }
            (f.get(c.get()) as android.widget.HorizontalScrollView).scrollTo(100_000, 0)
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREEN $name ${w}x$h -> ${File(dir, "$name.png").absolutePath} diag=${Diag.last(app)}")
        c.pause().stop().destroy()
    }

    @Test @RConfig(sdk = [35], qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun listLight() = shoot("list-light")

    @Test @RConfig(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun listDark() = shoot("list-dark")

    @Test @RConfig(sdk = [35], qualifiers = "w891dp-h411dp-land-notnight-xxhdpi")
    fun listLandscape() = shoot("list-landscape")

    /** Large phone font, scrolled all the way right: every number must still show in full. */
    @Test @RConfig(sdk = [35], qualifiers = "w384dp-h832dp-notnight-xxhdpi", fontScale = 1.3f)
    fun listBigFontScrolled() = shoot("list-bigfont-right", scrollRight = true)

    @Test @RConfig(sdk = [35], qualifiers = "w384dp-h832dp-notnight-xxhdpi", fontScale = 1.3f)
    fun listBigFont() = shoot("list-bigfont")

    @Test @RConfig(sdk = [35], qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    fun listNavy() = shoot("list-navy", appBg = 0xFF14213D.toInt())
}
