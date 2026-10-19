# Sample workloads

| File | Source | Format |
|---|---|---|
| nasa_week2_per_minute.csv | **Real**: NASA trace shipped with AutoScaleSim (`src/others/Dataset_NASA_July.tset`), days 8-14, not used by the default 7-day experiment | per-minute counts |
| synthetic_flash_crowd_3days.csv | **Synthetic**: NASA days 8-10 with an injected flash crowd (10-minute ramp to 3x, 40 minutes, day 3 at 14:00) | per-minute counts |
| synthetic_request_log_3h.csv | **Synthetic**: generated request log (seed 42), one row per request, ISO-8601 timestamps | request log |

Regenerate with `BudgetGuard samples`.
