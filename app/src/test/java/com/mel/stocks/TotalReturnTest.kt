package com.mel.stocks

import android.app.Application
import android.appwidget.AppWidgetManager
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.time.LocalDate
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [35])
class TotalReturnTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun d(s: String) = LocalDate.parse(s)

    /** Hand-checked numbers: the dividend paid inside the window shows up as the gap between close and adj. */
    @Test
    fun mathByHand() {
        val c = History.Closes(
            "2026-11-11",
            listOf(
                History.Day(d("2026-08-10"), 100.0, 98.0),
                History.Day(d("2026-11-10"), 105.0, 105.0),
            ),
            emptyMap(), "Test Co",
        )
        val today = d("2026-11-11") // Wednesday
        val m3 = History.totalReturn(c, History.Span.M3, 106.0, today)!!
        println("TR M3 no div today = $m3 (expect ${(106.0 / 98.0 - 1) * 100})")
        assertEquals((106.0 / 98.0 - 1) * 100, m3, 1e-9)

        val withDiv = c.copy(divs = mapOf(today to 1.0))
        val m3d = History.totalReturn(withDiv, History.Span.M3, 106.0, today)!!
        println("TR M3 ex-div today = $m3d (expect ${((105.0 / 98.0) * (107.0 / 105.0) - 1) * 100})")
        assertEquals(((105.0 / 98.0) * (107.0 / 105.0) - 1) * 100, m3d, 1e-9)

        assertNull("5D needs five sessions", History.totalReturn(c, History.Span.D5, 106.0, today))
        assertNull("3Y needs history that reaches back three years", History.totalReturn(c, History.Span.Y3, 106.0, today))

        // Saturday: the live price belongs to Friday's session, so Friday's own close is not the base for 1D.
        val fri = History.Closes(
            "2026-11-14",
            listOf(
                History.Day(d("2026-11-12"), 50.0, 50.0),
                History.Day(d("2026-11-13"), 51.0, 51.0),
            ),
            emptyMap(), null,
        )
        val sat = History.totalReturn(fri, History.Span.D1, 51.0, d("2026-11-14"))!!
        assertEquals((51.0 / 50.0 - 1) * 100, sat, 1e-9)
    }

    /**
     * Live cross-check: Yahoo's adjusted closes against reinvesting each dividend by hand at the prior close.
     * They should agree to within rounding for any name without a split in the window.
     */
    @Test
    fun liveAdjCloseMatchesDividendReinvestment() {
        val syms = listOf("T", "JNJ", "O", "KO", "SCHD", "XOM", "MU", "AAPL", "SNDK", "GC=F")
        val bad = mutableListOf<String>()
        for (s in syms) {
            val c = try { History.fetch(s) } catch (e: Exception) { println("XCHECK $s fetch failed: ${e.message}"); continue }
            val today = History.today()
            val session = if (today.dayOfWeek.value >= 6) c.days.last().date else today
            val done = c.days.filter { it.date < session }
            val last = done.last()
            val line = StringBuilder("XCHECK $s ${c.name} divs=${c.divs.size}")
            for (span in listOf(History.Span.M1, History.Span.M3, History.Span.Y1, History.Span.Y3)) {
                val target = session.minusMonths(span.months)
                val base = if (done.first().date > target) null else done.lastOrNull { it.date <= target }
                if (base == null) { line.append(" ${span.label}=n/a"); continue }
                val viaAdj = History.totalReturn(c, span, last.close, today)!!
                var growth = 1.0
                for ((ex, amt) in c.divs) {
                    if (ex <= base.date || ex > last.date) continue
                    val prior = done.lastOrNull { it.date < ex } ?: continue
                    growth *= 1.0 + amt / prior.close
                }
                val divToday = c.divs[session] ?: 0.0
                val byHand = (growth * (last.close / base.close) * ((last.close + divToday) / last.close) - 1.0) * 100.0
                val priceOnly = (last.close / base.close - 1.0) * 100.0
                line.append(String.format(" %s: adj=%.2f hand=%.2f price=%.2f", span.label, viaAdj, byHand, priceOnly))
                if (span == History.Span.Y1 && kotlin.math.abs(viaAdj - byHand) > 0.25) bad += "$s ${span.label} adj=$viaAdj hand=$byHand"
            }
            println(line)
        }
        println("XCHECK mismatches: $bad")
        assertTrue("adjclose and hand-reinvested dividends disagree: $bad", bad.isEmpty())
    }

    /** The list screen opens, draws every row and column, and sorts, without logging an error. */
    @Test
    fun listScreenRendersAndSorts() {
        Config.save(app, Config.load(app).copy(tickersText = "AAA\nBBB Label B\nCCC"))
        Store.record(app, listOf(
            Quotes.Result("AAA", "Aaa Inc", 110.0, 100.0, 10.0),
            Quotes.Result("BBB", "Bbb Corp", 50.0, 51.0, -1.96),
            Quotes.Result("CCC", error = "nope"),
        ))
        val today = History.today()
        val days = (0L..(3 * 366 + 30)).map { today.minusDays(it) }.filter { it.dayOfWeek.value <= 5 && it < today }
            .sortedBy { it.toEpochDay() }.mapIndexed { i, dd -> History.Day(dd, 100.0 + i * 0.01, 90.0 + i * 0.012) }
        History.save(app, "AAA", History.Closes(today.toString(), days, emptyMap(), "Aaa Incorporated"))
        History.save(app, "BBB", History.Closes(today.toString(), days.takeLast(100), emptyMap(), null))
        History.save(app, "CCC", History.Closes(today.toString(), days.takeLast(30), emptyMap(), null))

        val c = Robolectric.buildActivity(WatchlistActivity::class.java).setup()
        val a = c.get()
        val f = WatchlistActivity::class.java.getDeclaredField("bodyRows").apply { isAccessible = true }
        val rows = f.get(a) as LinearLayout
        println("LIST rows+dividers=${rows.childCount} diag=${Diag.last(app)}")
        for (span in History.Span.values()) {
            println("LIST AAA ${span.label} = ${History.totalReturn(app, "AAA", span, 110.0, 10.0)}  BBB = ${History.totalReturn(app, "BBB", span, 50.0, -1.96)}")
        }
        assertEquals(5, rows.childCount) // 3 rows + 2 dividers
        Notes.set(app, "AAA", "zebra")
        Notes.set(app, "BBB", "apple")
        TableSort.tap(app, "1Y", true)
        TableSort.tap(app, Columns.NOTES, false)
        val order = TableSort.apply(app, Config.load(app).tickers, Store.load(app)).map { it.symbol }
        println("LIST notes sort = $order")
        assertEquals(listOf("BBB", "AAA", "CCC"), order) // "apple", "zebra", then CCC with no note

        // Column order and a fixed notes width are honored on the next draw.
        Columns.setOrder(app, listOf("3Y", "1D", Columns.NOTES))
        Columns.setNotesWidthDp(app, 90)
        val headF = WatchlistActivity::class.java.getDeclaredField("headRow").apply { isAccessible = true }
        c.recreate()
        val a2 = c.get()
        val head = headF.get(a2) as LinearLayout
        val titles = (0 until head.childCount).map { (head.getChildAt(it) as android.widget.TextView).text.toString().removeSuffix(" ▲").removeSuffix(" ▼") }
        println("LIST headers = $titles")
        assertEquals(listOf("3Y", "1D", "Notes"), titles.take(3))
        assertEquals(Columns.ALL.size, titles.size)
        c.pause().stop().destroy()
        assertNull(Diag.last(app))
    }

    @Test
    fun widgetStillRenders() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.render(app)
        assertNull(Diag.last(app))
    }
}
