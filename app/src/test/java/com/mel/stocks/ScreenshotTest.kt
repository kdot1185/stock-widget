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

    private fun seed() {
        Config.save(app, Config.load(app).copy(tickersText = "MU\nSNDK\nGC=F Gold\nMETA\nKO\nO\nSCHD\nNVDA"))
        val rows = listOf(
            Triple("MU", 725.11, -3.2), Triple("SNDK", 859.99, -5.1), Triple("GC=F", 4012.3, 0.4),
            Triple("META", 641.39, 1.1), Triple("KO", 71.02, 0.2), Triple("O", 61.4, -0.3),
            Triple("SCHD", 29.88, 0.1), Triple("NVDA", 176.32, 4.46),
        )
        Store.record(app, rows.map { (s, p, c) -> Quotes.Result(s, null, p, p / (1 + c / 100), c) })
        val names = mapOf("MU" to "Micron Technology, Inc.", "SNDK" to "Sandisk Corporation", "META" to "Meta Platforms, Inc.",
            "KO" to "The Coca-Cola Company", "O" to "Realty Income Corporation", "SCHD" to "Schwab U.S. Dividend Equity ETF",
            "NVDA" to "NVIDIA Corporation", "GC=F" to "Gold Dec 26")
        val today = History.today()
        for ((i, r) in rows.withIndex()) {
            val (sym, price, _) = r
            val drift = listOf(0.0011, 0.0019, 0.0004, 0.0006, 0.0003, -0.0001, 0.0002, 0.0012)[i]
            val span = if (sym == "SNDK") 430L else 1130L
            val dates = (1L..span).map { today.minusDays(it) }.filter { it.dayOfWeek.value <= 5 }.sortedBy { it.toEpochDay() }
            val n = dates.size
            val days = dates.mapIndexed { k, d ->
                val close = price / Math.exp(drift * (n - k))
                History.Day(d, close, close * (1 - 0.00008 * (n - k)))
            }
            History.save(app, sym, History.Closes(today.toString(), days, emptyMap(), names[sym]))
        }
    }

    private fun shoot(name: String) {
        seed()
        val c = Robolectric.buildActivity(WatchlistActivity::class.java).setup()
        val root: View = c.get().window.decorView
        val dm = c.get().resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
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
}
