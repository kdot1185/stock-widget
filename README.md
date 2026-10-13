# Watchlist widget

Android home-screen watchlist styled after Yahoo Finance's widget, with the banner replaced by a thin "Updated" strip and compact rows.

- **Any JSON quote API.** Presets for Yahoo (no key), Finnhub, Twelve Data, Financial Modeling Prep and Alpha Vantage, or paste your own URL with `{symbol}` / `{key}` and point the price, previous-close, % change and name fields at the right spots in the JSON. "Test first ticker" shows the parsed values and the raw response.
- **Pre-market / after-hours.** With the Yahoo preset, prices include extended hours and get a PRE / AH tag.
- **Refresh.** Tap ⟳, a background refresh every N minutes (Android's floor is 15), and an exact-alarm refresh at a set morning time (default 06:55).
- Tap the "Updated" strip to open settings; tap a row to open its quote page.

Every push to `main` builds an APK and publishes it under Releases.
