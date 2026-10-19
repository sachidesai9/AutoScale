/*
 * Custom workload upload and predictive traffic-spike warnings (student extension).
 *
 * Nothing here changes AutoScaleSim. An uploaded workload is converted into the exact file format the
 * original EndUserEmulator already reads (a Neuroph DataSet, one row per minute:
 * input = [weekend, day, hour, minute, requestCount], desiredOutput = arrival second of each request),
 * so the unmodified emulator replays it.
 *
 * Forecasting method (documented in the report):
 *   - requests are aggregated into buckets equal to the scaling interval (default 10 min);
 *   - Holt-Winters additive smoothing with damped trend and a daily season when at least two days of data
 *     exist, otherwise damped Holt (no season); alpha/beta/gamma chosen by grid search on the series itself;
 *   - a bucket is a SPIKE when it exceeds mean + k * std of the trailing 2-hour window (k default 2.5)
 *     and is at least 1.2 x that mean;
 *   - a WARNING is raised at bucket t for bucket t+h when the forecast's upper bound
 *     (forecast + 1.645 * std of past h-step errors) crosses the spike threshold known at t.
 */
package budgetguard;

import autoscalesim.log.AutoScaleSimTags;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Workload {

    public static final int MIN_MINUTES = 120;          // forecasting needs some history
    public static final int MAX_MINUTES = 7 * 1440;     // keeps one simulation under a minute
    public static final int MAX_PER_MINUTE = 2000;      // keeps memory bounded (2000 requests/min = 33 req/s)
    public static final int NASA_OUTPUT_SIZE = 200;     // width of the original NASA DataSet rows

    public final String name;
    public final String format;        // "per-minute counts" or "request log"
    public final double[] perMinute;
    public final List<String> notes = new ArrayList<>();

    Workload(String name, String format, double[] perMinute) {
        this.name = name;
        this.format = format;
        this.perMinute = perMinute;
    }

    // ------------------------------------------------------------------ parsing and validation

    /**
     * Accepts two CSV layouts (header row required):
     * (a) per-minute counts: a column named requests / count / requests_per_minute / hits; one row per
     * consecutive minute (any other columns, e.g. a timestamp, are ignored);
     * (b) request log: a column named timestamp / time / datetime and no count column; one row per request,
     * bucketed into minutes from the first timestamp. Timestamps may be epoch seconds or ISO-8601.
     */
    public static Workload parse(String name, String csv) {
        if (csv == null || csv.trim().isEmpty()) throw new IllegalArgumentException("The file is empty");
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) if (!line.trim().isEmpty()) rows.add(splitCsvLine(line));
        if (rows.size() < 2) throw new IllegalArgumentException("Need a header row and at least one data row");
        String[] header = rows.get(0);
        int countCol = -1, timeCol = -1;
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (h.equals("requests") || h.equals("count") || h.equals("requests_per_minute") || h.equals("hits") || h.equals("request_count"))
                countCol = i;
            if (h.equals("timestamp") || h.equals("time") || h.equals("datetime") || h.equals("date_time")) timeCol = i;
        }
        if (countCol >= 0) {
            double[] v = new double[rows.size() - 1];
            for (int r = 1; r < rows.size(); r++) {
                String cell = countCol < rows.get(r).length ? rows.get(r)[countCol].trim() : "";
                double x;
                try { x = Double.parseDouble(cell); } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Line " + (r + 1) + ": '" + cell + "' is not a number");
                }
                if (Double.isNaN(x) || Double.isInfinite(x) || x < 0)
                    throw new IllegalArgumentException("Line " + (r + 1) + ": request count must be a non-negative number");
                v[r - 1] = x;
            }
            Workload w = new Workload(name, "per-minute counts", v);
            w.finishValidation();
            return w;
        }
        if (timeCol >= 0) {
            long[] ts = new long[rows.size() - 1];
            for (int r = 1; r < rows.size(); r++) {
                String cell = timeCol < rows.get(r).length ? rows.get(r)[timeCol].trim() : "";
                ts[r - 1] = parseTimestamp(cell, r + 1);
            }
            Arrays.sort(ts);
            long span = (ts[ts.length - 1] - ts[0]) / 60 + 1;
            if (span > MAX_MINUTES) throw new IllegalArgumentException("The log spans " + span + " minutes; the maximum is " + MAX_MINUTES + " (7 days)");
            double[] v = new double[(int) span];
            for (long t : ts) v[(int) ((t - ts[0]) / 60)]++;
            Workload w = new Workload(name, "request log", v);
            w.notes.add("Request log with " + ts.length + " requests bucketed into " + span + " minutes from the first timestamp.");
            w.finishValidation();
            return w;
        }
        throw new IllegalArgumentException("Header must contain a 'requests' column (per-minute counts) or a 'timestamp' column (request log)");
    }

    private void finishValidation() {
        if (perMinute.length < MIN_MINUTES)
            throw new IllegalArgumentException("Need at least " + MIN_MINUTES + " minutes of data (got " + perMinute.length + ")");
        if (perMinute.length > MAX_MINUTES)
            throw new IllegalArgumentException("At most " + MAX_MINUTES + " minutes (7 days) are supported (got " + perMinute.length + ")");
        int rounded = 0;
        for (int i = 0; i < perMinute.length; i++) {
            if (perMinute[i] > MAX_PER_MINUTE)
                throw new IllegalArgumentException("Minute " + (i + 1) + " has " + (long) perMinute[i] + " requests; the maximum is " + MAX_PER_MINUTE);
            double r = Math.rint(perMinute[i]);
            if (r != perMinute[i]) rounded++;
            perMinute[i] = r;
        }
        if (rounded > 0) notes.add(rounded + " fractional counts were rounded to whole requests.");
        if (total() == 0) throw new IllegalArgumentException("The workload contains no requests");
    }

    static long parseTimestamp(String s, int line) {
        try {
            double d = Double.parseDouble(s);
            return (long) (d > 1e11 ? d / 1000 : d);               // epoch milliseconds or seconds
        } catch (NumberFormatException ignored) { }
        try { return OffsetDateTime.parse(s).toEpochSecond(); } catch (DateTimeParseException ignored) { }
        try { return Instant.parse(s).getEpochSecond(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(s.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC); } catch (DateTimeParseException ignored) { }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss", Locale.ENGLISH)).toEpochSecond(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) { }
        throw new IllegalArgumentException("Line " + line + ": cannot read timestamp '" + s + "' (use epoch seconds or ISO-8601)");
    }

    static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',' || ch == ';') { cells.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        cells.add(cur.toString());
        return cells.toArray(new String[0]);
    }

    public long total() { long t = 0; for (double v : perMinute) t += (long) v; return t; }

    public double peak() { double p = 0; for (double v : perMinute) p = Math.max(p, v); return p; }

    public double mean() { return total() / (double) perMinute.length; }

    // ------------------------------------------------------------------ replay file for the original emulator

    /**
     * Writes the workload in the original NASA DataSet layout. Arrival seconds inside a minute are spread
     * evenly (second = floor(i * 60 / n)), sorted like the original trace. Only input[4] and the
     * desiredOutput are read by EndUserEmulator.workloadGeneratorNASA.
     */
    public void writeTset(Path file) { writeTset(file, ARRIVALS_RANDOM_UNIFORM); }

    public static final String ARRIVALS_RANDOM_UNIFORM = "random-uniform", ARRIVALS_EVEN = "even";
    public static final long ARRIVAL_SEED = 20210101L;

    /**
     * Arrival seconds inside each minute: "random-uniform" (default) draws whole seconds 0-59 uniformly at
     * random with a fixed seed and sorts them, which matches the measured NASA trace (uniform across the minute,
     * many requests sharing a second); "even" spaces them evenly (kept only for the validation experiment).
     */
    public void writeTset(Path file, String arrivals) { writeTset(file, arrivals, ARRIVAL_SEED); }

    public void writeTset(Path file, String arrivals, long seed) {
        int width = (int) Math.max(NASA_OUTPUT_SIZE, peak());
        DataSet ds = new DataSet(5, width);
        java.util.Random rnd = new java.util.Random(seed);
        for (int m = 0; m < perMinute.length; m++) {
            int n = (int) perMinute[m];
            int day = m / 1440 + 1, hour = (m % 1440) / 60, minute = m % 60;
            double[] in = {0, day, hour, minute, n};
            double[] out = new double[width];
            for (int i = 0; i < n; i++)
                out[i] = arrivals.equals(ARRIVALS_EVEN) ? Math.floor(i * 60.0 / n) : rnd.nextInt(60);
            Arrays.sort(out, 0, n);
            ds.addRow(new DataSetRow(in, out));
        }
        ds.save(file.toString());
    }

    /** Per-minute CSV in the format this class parses (used for samples and downloads). */
    public static void writeCsv(Path file, double[] perMinute, String comment) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("minute,requests\n");
        for (int i = 0; i < perMinute.length; i++) s.append(i).append(',').append((long) perMinute[i]).append('\n');
        Files.write(file, s.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ analysis

    public static final class Params {
        public int bucketMin = 10;        // = AutoScaleSim scaling interval
        public int leadBuckets = 3;       // warn 30 minutes ahead (> 5 min VM start-up + 10 min interval)
        public double k = 2.5;            // spike sensitivity
        public int windowBuckets = 12;    // trailing 2 hours
        public double targetUtil = 0.70;  // = original CPU scale-up threshold (70 %)
        public int futureBuckets = 144;   // forecast the next 24 hours
        public int vmConfig = AutoScaleSimTags.VM_CONFIG_T2MEDIUM;
        public int cloudletLength = 5000, pes = 2;
        public double inrPerUsd = BudgetGuard.DEFAULT_INR_PER_USD;

        public void validate() {
            if (bucketMin < 1 || bucketMin > 60) throw new IllegalArgumentException("Bucket must be 1-60 minutes");
            if (leadBuckets < 1 || leadBuckets > 36) throw new IllegalArgumentException("Lead time must be 1-36 buckets");
            if (!(k >= 1 && k <= 6)) throw new IllegalArgumentException("Spike sensitivity k must be between 1 and 6");
            if (!(targetUtil > 0.1 && targetUtil <= 1)) throw new IllegalArgumentException("Target utilisation must be in (0.1, 1]");
            if (futureBuckets < 1 || futureBuckets > 1008) throw new IllegalArgumentException("Forecast horizon must be 1-1008 buckets");
            if (!(inrPerUsd > 0)) throw new IllegalArgumentException("Exchange rate must be positive");
        }

        /** Requests per minute one VM completes at 100 % CPU (VM MIPS x PEs vs request length x PEs). */
        public double vmCapacityPerMinute() {
            return 60.0 * AutoScaleSimTags.VM_MIPS[vmConfig] * AutoScaleSimTags.VM_PES[vmConfig] / ((double) cloudletLength * pes);
        }

        public double priceUsdPerHour() { return AutoScaleSimTags.VM_PRICE_ONDEMAND[vmConfig]; }
    }

    public static final class Model {
        public double alpha, beta, gamma, phi = 0.98, rho = 1;
        public int season;     // 0 = no season
        public String describe() {
            return season > 0
                    ? String.format(Locale.ROOT, "Holt-Winters additive, damped trend, burst-robust (alpha=%.2f, beta=%.2f, gamma=%.2f, phi=%.2f, rho=%.1f, season=%d buckets)", alpha, beta, gamma, phi, rho, season)
                    : String.format(Locale.ROOT, "Damped Holt, burst-robust (alpha=%.2f, beta=%.2f, phi=%.2f, rho=%.1f); less than two days of data, so no daily season", alpha, beta, phi, rho);
        }
    }

    public static final class Analysis {
        public Params p;
        public Model model;
        public double[] bucketRate;       // requests per minute in each bucket (mean)
        public double[] fcHstep;          // forecast made h buckets earlier (NaN where not available)
        public double[] upper;            // upper bound of that forecast
        public double[] threshold;        // spike threshold known at that bucket (NaN in warm-up)
        public boolean[] spike, warned;   // actual spike; warning issued h buckets earlier for this bucket
        public int[] requiredVms;
        public double[] futureRate, futureUpper;
        public int[] futureVms;
        public boolean[] futureSpike;
        public double futureThreshold;
        public Map<String, Object> summary = new LinkedHashMap<>();
    }

    public static double[] bucketize(double[] perMinute, int bucketMin) {
        int n = perMinute.length / bucketMin;                       // incomplete last bucket is dropped
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < bucketMin; j++) s += perMinute[i * bucketMin + j];
            b[i] = s / bucketMin;
        }
        return b;
    }

    /** h-step-ahead forecasts made at every point t (index t+h), for a given model. Also returns final state forecasts. */
    /**
     * h-step-ahead forecasts made at every point t (stored at index t+h) and the forecasts beyond the data.
     * x is the observed series; xc the same series with one-off bursts replaced (see analyse). The trend and the
     * daily season are learned from xc only, so a finished burst cannot leave a lasting rise or come back the next
     * day. Two levels are tracked: one on xc and one on x; the gap between them (an ongoing burst) is assumed to
     * fade by a factor rho per bucket ahead. rho = 0 ignores bursts, rho = 1 follows the raw level.
     */
    static double[][] run(double[] x, double[] xc, Model m, int h, int future) {
        int n = x.length, s = m.season;
        double[] fc = new double[n];
        Arrays.fill(fc, Double.NaN);
        double level, levelC, trend;
        double[] seas = new double[Math.max(1, s)];
        int start;
        if (s > 0) {
            double m1 = 0, m2 = 0;
            for (int i = 0; i < s; i++) { m1 += xc[i]; m2 += xc[i + s]; }
            m1 /= s; m2 /= s;
            level = levelC = m1;
            trend = (m2 - m1) / s;
            for (int i = 0; i < s; i++) seas[i] = xc[i] - m1;
            start = s;
        } else {
            level = levelC = xc[0];
            trend = n > 1 ? xc[1] - xc[0] : 0;
            start = 1;
        }
        for (int t = start; t < n; t++) {
            int target = t - 1 + h;                                  // forecast made at time t-1 for t-1+h
            if (target < n) fc[target] = Math.max(0, forecast(level, levelC, trend, seas, s, t - 1, h, m));
            double si = s > 0 ? seas[t % s] : 0;
            double prevC = levelC;
            level = m.alpha * (x[t] - si) + (1 - m.alpha) * (level + m.phi * trend);
            levelC = m.alpha * (xc[t] - si) + (1 - m.alpha) * (levelC + m.phi * trend);
            trend = m.beta * (levelC - prevC) + (1 - m.beta) * m.phi * trend;
            if (s > 0) seas[t % s] = m.gamma * (xc[t] - levelC) + (1 - m.gamma) * si;
        }
        double[] fut = new double[future];
        for (int k = 1; k <= future; k++) fut[k - 1] = Math.max(0, forecast(level, levelC, trend, seas, s, n - 1, k, m));
        return new double[][]{fc, fut};
    }

    static double forecast(double level, double levelC, double trend, double[] seas, int s, int t, int h, Model m) {
        double damp = 0, p = 1;
        for (int i = 1; i <= h; i++) { p *= m.phi; damp += p; }
        return levelC + (level - levelC) * Math.pow(m.rho, h) + damp * trend + (s > 0 ? seas[(t + h) % s] : 0);
    }

    static double mae(double[] a, double[] f, int from) {
        double s = 0; int c = 0;
        for (int i = from; i < a.length; i++) if (!Double.isNaN(f[i])) { s += Math.abs(a[i] - f[i]); c++; }
        return c == 0 ? Double.NaN : s / c;
    }

    static Model fit(double[] x, double[] xc, int h, int season, int minFrom) {
        double[] alphas = {0.1, 0.2, 0.4, 0.6, 0.8}, betas = {0.0, 0.02, 0.1}, gammas = season > 0 ? new double[]{0.05, 0.15, 0.3} : new double[]{0};
        double[] rhos = {0, 0.5, 0.8, 1};
        Model best = null;
        double bestErr = Double.MAX_VALUE;
        int from = season > 0 ? 2 * season : Math.min(x.length - 1, Math.max(minFrom, 3));
        for (double a : alphas) for (double b : betas) for (double g : gammas) for (double r : rhos) {
            Model m = new Model();
            m.alpha = a; m.beta = b; m.gamma = g; m.rho = r; m.season = season;
            double err = mae(x, run(x, xc, m, h, 0)[0], from);          // judged against the real data
            if (err < bestErr) { bestErr = err; best = m; }
        }
        return best;
    }

    public Analysis analyse(Params p) {
        p.validate();
        Analysis a = new Analysis();
        a.p = p;
        double[] x = bucketize(perMinute, p.bucketMin);
        int n = x.length;
        List<String> notes = new ArrayList<>();
        // Short uploads: shrink the spike window and, only if needed, the warning lead, and say so.
        int W = Math.min(p.windowBuckets, Math.max(3, n / 4));
        if (n < W + 1 + 4)
            throw new IllegalArgumentException("Too little data for " + p.bucketMin + "-minute buckets: need at least " + (W + 5) * p.bucketMin + " minutes");
        int h = Math.min(p.leadBuckets, n - W - 4);
        if (W < p.windowBuckets)
            notes.add("Short workload: the spike window was reduced from " + p.windowBuckets * p.bucketMin + " to " + W * p.bucketMin + " minutes.");
        if (h < p.leadBuckets)
            notes.add("Short workload: the warning lead was reduced from " + p.leadBuckets * p.bucketMin + " to " + h * p.bucketMin + " minutes.");
        int perDay = 1440 / p.bucketMin;
        int season = (1440 % p.bucketMin == 0 && n >= 2 * perDay + h) ? perDay : 0;

        // spike thresholds from the trailing window (on the observed data)
        a.threshold = new double[n];
        a.spike = new boolean[n];
        Arrays.fill(a.threshold, Double.NaN);
        for (int t = W; t < n; t++) {
            a.threshold[t] = threshold(x, t - W, t, p.k);
            a.spike[t] = x[t] > a.threshold[t] && x[t] >= 1.2 * meanOf(x, t - W, t);
        }

        // Robust fitting: isolated bursts of up to MAX_BURST_MINUTES are replaced by the preceding window's mean
        // before the model is fitted, so a one-off burst does not pull the level, trend or daily season upwards.
        // Longer runs are kept: they are genuine level changes. Errors are still measured against the real data.
        double[] xc = x.clone();
        int maxRun = Math.max(1, MAX_BURST_MINUTES / p.bucketMin), cleaned = 0;
        for (int t = W; t < n; ) {
            if (!a.spike[t]) { t++; continue; }
            int end = t;
            while (end < n && a.spike[end]) end++;
            if (end - t <= maxRun) {
                double base = meanOf(x, t - W, t);
                for (int i = t; i < end; i++) { xc[i] = base; cleaned++; }
            }
            t = end;
        }
        if (cleaned > 0)
            notes.add(cleaned + " burst bucket(s) of at most " + MAX_BURST_MINUTES + " minutes were treated as one-off outliers when fitting the forecast.");
        a.model = fit(x, xc, h, season, W);
        double[][] r = run(x, xc, a.model, h, p.futureBuckets);
        a.bucketRate = x;
        a.fcHstep = r[0];
        a.futureRate = r[1];

        // warnings: forecast issued at t-h for t, compared with the threshold known at t-h
        a.upper = new double[n];
        a.warned = new boolean[n];
        Arrays.fill(a.upper, Double.NaN);
        List<Double> errs = new ArrayList<>();
        int warmup = Math.max(W, season > 0 ? 2 * season : 0) + h;
        for (int t = 0; t < n; t++) {
            if (Double.isNaN(a.fcHstep[t])) continue;
            int issued = t - h;
            double sd = errs.size() >= MIN_ERRORS ? robustStd(errs) : Double.NaN;
            if (!Double.isNaN(sd) && issued >= W) {
                a.upper[t] = a.fcHstep[t] + 1.645 * sd;
                double thrAtIssue = a.threshold[issued];
                a.warned[t] = t >= warmup && a.upper[t] > thrAtIssue && a.fcHstep[t] >= 1.2 * meanOf(x, issued - W, issued);
            }
            errs.add(x[t] - a.fcHstep[t]);           // the error becomes known only at time t
        }

        // backtest scores (after warm-up)
        int tp = 0, fp = 0, fn = 0, spikes = 0;
        for (int t = warmup; t < n; t++) {
            if (a.spike[t]) spikes++;
            if (a.spike[t] && a.warned[t]) tp++;
            else if (a.warned[t]) fp++;
            else if (a.spike[t]) fn++;
        }
        double[] naive = new double[n], snaive = new double[n];
        Arrays.fill(naive, Double.NaN);
        Arrays.fill(snaive, Double.NaN);
        for (int t = h; t < n; t++) naive[t] = x[t - h];
        if (season > 0) for (int t = season; t < n; t++) snaive[t] = x[t - season];

        // capacity and cost (simulator's own VM capacity and hourly price)
        double cap = p.vmCapacityPerMinute() * p.targetUtil;
        a.requiredVms = new int[n];
        for (int t = 0; t < n; t++) a.requiredVms[t] = Math.max(1, (int) Math.ceil(x[t] / cap));

        // future: never forecast further ahead than the history we have
        int fut = Math.min(p.futureBuckets, n);
        a.futureRate = Arrays.copyOf(a.futureRate, fut);
        a.futureUpper = new double[fut];
        a.futureVms = new int[fut];
        a.futureSpike = new boolean[fut];
        double sdH = errs.size() >= MIN_ERRORS ? robustStd(errs) : 0;   // robust spread of h-step errors
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        // a predicted spike must exceed the 95th percentile of observed demand AND be at least 1.2 x its median,
        // so ordinary noise on flat traffic is never reported as a spike
        a.futureThreshold = Math.max(sorted[(int) Math.floor(0.95 * (n - 1))], 1.2 * median(x));
        for (int i = 0; i < fut; i++) {
            double widen = Math.min(2.5, Math.sqrt(Math.max(1.0, (i + 1) / (double) h)));
            a.futureUpper[i] = a.futureRate[i] + 1.645 * sdH * widen;
            a.futureVms[i] = Math.max(1, (int) Math.ceil(a.futureUpper[i] / cap));
            a.futureSpike[i] = a.futureRate[i] > a.futureThreshold;
        }
        if (fut < p.futureBuckets)
            notes.add("The forecast horizon was shortened to " + fut * p.bucketMin / 60.0 + " hours: it cannot be longer than the uploaded history.");
        if (errs.size() < MIN_ERRORS)
            notes.add("Too few past forecasts to estimate an error band, so no warnings are issued and the upper bound equals the forecast.");
        if (!notes.isEmpty()) a.summary.put("note", String.join(" ", notes));

        Map<String, Object> s = a.summary;
        s.put("minutes", perMinute.length);
        s.put("total_requests", total());
        s.put("mean_req_per_min", round(mean(), 2));
        s.put("peak_req_per_min", round(peak(), 0));
        s.put("bucket_minutes", p.bucketMin);
        s.put("model", a.model.describe());
        s.put("forecast_lead_minutes", h * p.bucketMin);
        s.put("spike_window_minutes", W * p.bucketMin);
        s.put("burst_buckets_cleaned", cleaned);
        s.put("mae_model", round(mae(x, a.fcHstep, warmup), 3));
        s.put("mae_naive_last_value", round(mae(x, naive, warmup), 3));
        if (season > 0) s.put("mae_seasonal_naive", round(mae(x, snaive, warmup), 3));
        s.put("spikes_detected", spikes);
        s.put("warnings_issued", tp + fp);
        s.put("warning_precision", tp + fp == 0 ? null : round(tp / (double) (tp + fp), 3));
        s.put("warning_recall", spikes == 0 ? null : round(tp / (double) spikes, 3));
        s.put("vm_capacity_req_per_min", round(p.vmCapacityPerMinute(), 2));
        s.put("target_utilisation", p.targetUtil);
        s.put("max_required_vms_observed", maxOf(a.requiredVms));
        s.put("estimated_cost_observed_inr", round(costUsd(a.requiredVms, p) * p.inrPerUsd, 2));
        s.put("future_hours", round(a.futureRate.length * p.bucketMin / 60.0, 1));
        s.put("future_spike_threshold_req_per_min", round(a.futureThreshold, 1));
        s.put("future_peak_req_per_min_upper", round(maxOf(a.futureUpper), 1));
        s.put("future_max_required_vms", maxOf(a.futureVms));
        int fs = 0; for (boolean b : a.futureSpike) if (b) fs++;
        s.put("future_spike_buckets", fs);
        s.put("estimated_cost_future_inr", round(costUsd(a.futureVms, p) * p.inrPerUsd, 2));
        return a;
    }

    /** Hourly billing approximation: each clock hour is charged at the peak VM count of its buckets. */
    static double costUsd(int[] vms, Params p) {
        int perHour = Math.max(1, 60 / p.bucketMin);
        double usd = 0;
        for (int i = 0; i < vms.length; i += perHour) {
            int mx = 0;
            for (int j = i; j < Math.min(vms.length, i + perHour); j++) mx = Math.max(mx, vms[j]);
            usd += mx * p.priceUsdPerHour();
        }
        return usd;
    }

    static double threshold(double[] x, int from, int to, double k) {
        double mu = meanOf(x, from, to), var = 0;
        for (int i = from; i < to; i++) var += (x[i] - mu) * (x[i] - mu);
        return mu + k * Math.sqrt(var / Math.max(1, to - from - 1));
    }

    static double meanOf(double[] x, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += x[i];
        return to > from ? s / (to - from) : 0;
    }

    static final int MAX_BURST_MINUTES = 60, MIN_ERRORS = 5;

    /** 1.4826 x median absolute deviation: a standard deviation estimate that one burst cannot inflate. */
    static double robustStd(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        double med = median(a);
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - med);
        double mad = 1.4826 * median(dev);
        return mad > 0 ? mad : std(v);
    }

    static double median(double[] a) {
        double[] b = a.clone();
        Arrays.sort(b);
        int m = b.length / 2;
        return b.length % 2 == 1 ? b[m] : (b[m - 1] + b[m]) / 2;
    }

    static double std(List<Double> v) {
        double mu = 0;
        for (double d : v) mu += d;
        mu /= v.size();
        double s = 0;
        for (double d : v) s += (d - mu) * (d - mu);
        return Math.sqrt(s / Math.max(1, v.size() - 1));
    }

    static double maxOf(double[] v) { double m = 0; for (double d : v) m = Math.max(m, d); return m; }

    static int maxOf(int[] v) { int m = 0; for (int d : v) m = Math.max(m, d); return m; }

    static Double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    // ------------------------------------------------------------------ sample files

    /** Per-minute counts of the original NASA trace, rows [fromMinute, toMinute). */
    public static double[] nasaMinutes(Path tset, int fromMinute, int toMinute) {
        DataSet d = DataSet.load(tset.toString());
        double[] v = new double[toMinute - fromMinute];
        for (int i = fromMinute; i < toMinute; i++) v[i - fromMinute] = d.getRowAt(i).getInput()[4];
        return v;
    }
}
