# Watchlist widget

Android home-screen watchlist styled after Yahoo Finance's widget, with the banner replaced by a thin "Updated" strip and compact rows.

- **Any JSON quote API.** Presets for Yahoo (no key), Alpaca (key + secret), Finnhub, Twelve Data, Financial Modeling Prep and Alpha Vantage, or paste your own URL with `{symbol}` / `{key}` and point the price, previous-close, % change and name fields at the right spots in the JSON. "Test first ticker" shows the parsed values and the raw response.
- **Alpaca.** Paste the API key ID and secret from your Alpaca dashboard. The preset uses the free IEX feed, which covers one exchange and trades roughly 8:00–17:00 ET, so before 8 AM you'll see the prior session's last trade. With a paid SIP subscription, change `feed=iex` to `feed=sip` in the URL. Stocks and ETFs only; futures like GC=F won't resolve.
- **Pre-market / after-hours.** With the Yahoo preset, prices include extended hours and get a PRE / AH tag.
- **Full list.** The app icon opens the whole watchlist with every column: symbol (pinned), company name, last price, and total return over 1D, 5D, 1M, 3M, 6M, 1Y and 3Y. The table scrolls sideways; tap a header to sort; ⚙ opens settings.
- **Total returns.** Every return column, on the widget too, adds back dividends: three years of daily closes, dividend-adjusted closes and dividends come from Yahoo's chart endpoint once per symbol per day, whatever the quote source.
- **Refresh.** Tap ⟳, a background refresh every N minutes (Android's floor is 15), and an exact-alarm refresh at a set morning time (default 06:55).
- Tap the "Updated" strip to open settings; tap a row to open its quote page.

Every push to `main` builds an APK and publishes it under Releases.
