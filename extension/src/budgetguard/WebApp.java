package budgetguard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web application: static dashboard + JSON API. Every number it shows comes from a real AutoScaleSim run,
 * the extension's documented calculations, or the database.
 */
public final class WebApp {

    static final int MAX_BODY = 20 * 1024 * 1024;
    static final int MAX_QUEUED = 3;
    static final long DUPLICATE_WINDOW_MS = 5000;
    String lastParams, lastRunId;
    long lastSubmitMillis;

    final Store store;
    final Path dataDir;       // AutoScaleSim src/others (datasets and committed outputs)
    final Path resultsDir;    // DATA_DIR/results
    final Path webDir;        // static files (null = classpath)
    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "simulation-worker"); t.setDaemon(true); return t; });
    final AtomicInteger pending = new AtomicInteger();
    final Map<String, StringBuffer> logs = new ConcurrentHashMap<>();
    HttpServer server;

    public WebApp(Store store, Path dataDir, Path resultsDir, Path webDir) {
        this.store = store;
        this.dataDir = dataDir;
        this.resultsDir = resultsDir;
        this.webDir = webDir;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> o = BudgetGuard.args(args, 0);
        Path data = Paths.get(o.getOrDefault("data-dir", env("DATA_DIR", "data"))).toAbsolutePath();
        Path web = Paths.get(o.getOrDefault("web", env("WEB_DIR", "extension/web")));
        int port = Integer.parseInt(o.getOrDefault("port", env("PORT", "8080")));
        Store store = Store.open(data);
        WebApp app = new WebApp(store, BudgetGuard.findDataDir(o), data.resolve("results"), Files.isDirectory(web) ? web : null);
        app.start(port);
        System.out.println("AutoScaleSim dashboard on http://localhost:" + port + "/  (database: " + store.kind + ", data: " + data + ")");
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isEmpty() ? d : v; }

    public void start(int port) throws Exception {
        Files.createDirectories(resultsDir);
        int failed = store.failInterrupted();
        if (failed > 0) System.out.println("Marked " + failed + " interrupted run(s) as failed");
        seedSamples();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/", this::api);
        server.createContext("/files/", this::files);
        server.createContext("/", this::staticFile);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        worker.shutdownNow();
    }

    void seedSamples() throws Exception {
        if (store.count("workloads") > 0) return;
        Path samples = dataDir.resolve("../../samples").normalize();
        if (!Files.isDirectory(samples)) samples = Paths.get("samples");
        if (!Files.isDirectory(samples)) return;
        for (String f : new String[]{"nasa_week2_per_minute.csv", "synthetic_flash_crowd_3days.csv", "synthetic_request_log_3h.csv"}) {
            Path p = samples.resolve(f);
            if (!Files.exists(p)) continue;
            Workload w = Workload.parse("sample: " + f, new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            store.addWorkload(w, normalisedCsv(w));
        }
    }

    static String normalisedCsv(Workload w) {
        StringBuilder s = new StringBuilder("minute,requests\n");
        for (int i = 0; i < w.perMinute.length; i++) s.append(i).append(',').append((long) w.perMinute[i]).append('\n');
        return s.toString();
    }

    // ------------------------------------------------------------------ API

    void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String[] seg = path.substring("/api/".length()).split("/");
            if (seg[0].equals("health")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "ok");
                m.put("database", store.kind);
                m.put("runs", store.count("runs"));
                m.put("dataset_present", Files.exists(dataDir.resolve("Dataset_NASA_July.tset")));
                json(ex, 200, m);
            } else if (seg[0].equals("info")) {
                json(ex, 200, info());
            } else if (seg[0].equals("runs") && seg.length == 1 && method.equals("POST")) {
                json(ex, 202, submit(form(readBody(ex, 64 * 1024))));
            } else if (seg[0].equals("runs") && seg.length == 1) {
                List<Map<String, Object>> runs = store.listRuns(Integer.parseInt(q.getOrDefault("limit", "50")));
                for (Map<String, Object> r : runs) r.put("metrics", store.getMetrics((String) r.get("id")));
                json(ex, 200, runs);
            } else if (seg[0].equals("runs") && seg.length == 2) {
                Map<String, Object> r = store.getRun(seg[1]);
                if (r == null) { json(ex, 404, err("Run not found")); return; }
                r.put("metrics", store.getMetrics(seg[1]));
                StringBuffer log = logs.get(seg[1]);
                r.put("log", log == null ? "" : log.toString());
                json(ex, 200, r);
            } else if (seg[0].equals("runs") && seg.length == 3 && seg[2].equals("series")) {
                json(ex, 200, series(seg[1]));
            } else if (seg[0].equals("runs") && seg.length == 3 && seg[2].equals("decisions")) {
                json(ex, 200, decisions(seg[1], q.getOrDefault("variant", "guard")));
            } else if (seg[0].equals("workloads") && seg.length == 1 && method.equals("POST")) {
                String name = q.getOrDefault("name", "upload.csv");
                if (!name.matches("[\\w .()\\-]{1,120}")) name = "upload.csv";
                String csv = new String(readBody(ex, MAX_BODY), StandardCharsets.UTF_8);
                Workload w = Workload.parse(name, csv);
                String id = store.addWorkload(w, normalisedCsv(w));
                Map<String, Object> m = new LinkedHashMap<>(store.getWorkload(id));
                m.remove("csv");
                json(ex, 201, m);
            } else if (seg[0].equals("workloads") && seg.length == 1) {
                json(ex, 200, store.listWorkloads());
            } else if (seg[0].equals("workloads") && seg.length == 3 && seg[2].equals("analysis")) {
                Workload w = store.loadWorkload(seg[1]);
                if (w == null) { json(ex, 404, err("Workload not found")); return; }
                json(ex, 200, analysisJson(w, params(q)));
            } else if (seg[0].equals("workloads") && seg.length == 3 && seg[2].equals("csv")) {
                Map<String, Object> m = store.getWorkload(seg[1]);
                if (m == null) { json(ex, 404, err("Workload not found")); return; }
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"workload-" + seg[1] + ".csv\"");
                send(ex, 200, "text/csv; charset=utf-8", ((String) m.get("csv")).getBytes(StandardCharsets.UTF_8));
            } else {
                json(ex, 404, err("Unknown API path"));
            }
        } catch (IllegalArgumentException e) {
            json(ex, 400, err(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, err("Server error: " + e.getMessage()));
        }
    }

    Map<String, Object> info() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Reproduction and Extension of AutoScaleSim for Budget-Aware, Explainable and Predictive Cloud Auto-Scaling");
        m.put("paper", "M. S. Aslanpour, A. N. Toosi, J. Taheri and R. Gaire, \"AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds\", Simulation Modelling Practice and Theory, vol. 108, art. 102245, 2021.");
        m.put("doi", "https://doi.org/10.1016/j.simpat.2020.102245");
        m.put("repository", "https://github.com/aslanpour/AutoScaleSim");
        m.put("database", store.kind);
        m.put("runs", store.count("runs"));
        m.put("workloads", store.count("workloads"));
        m.put("dataset_present", Files.exists(dataDir.resolve("Dataset_NASA_July.tset")));
        m.put("price_usd_per_hour_t2medium", autoscalesim.log.AutoScaleSimTags.VM_PRICE_ONDEMAND[autoscalesim.log.AutoScaleSimTags.VM_CONFIG_T2MEDIUM]);
        m.put("default_inr_per_usd", BudgetGuard.DEFAULT_INR_PER_USD);
        Map<String, Object> latest = null;
        for (Map<String, Object> r : store.listRuns(200))
            if ("reproduce".equals(r.get("kind")) && "done".equals(r.get("status"))
                    && String.valueOf(r.get("params")).contains("\"scenario\":\"nasa\"")) { latest = r; break; }
        m.put("latest_reproduction", latest);
        m.put("queue_pending", pending.get());
        return m;
    }

    // ------------------------------------------------------------------ runs

    Map<String, Object> submit(Map<String, String> f) throws Exception {
        String kind = f.getOrDefault("kind", "");
        if (!Arrays.asList("compare", "reproduce", "workload", "sweep").contains(kind))
            throw new IllegalArgumentException("kind must be compare, reproduce, workload or sweep");
        // validate everything before queueing
        BudgetGuard.GuardConfig cfg = new BudgetGuard.GuardConfig(num(f, "budgetInr", 2000), num(f, "targetRt", 4.0), num(f, "inrPerUsd", BudgetGuard.DEFAULT_INR_PER_USD));
        String scenario = f.getOrDefault("scenario", "nasa");
        if (kind.equals("reproduce") && !scenario.equals("nasa") && !scenario.equals("wikipedia"))
            throw new IllegalArgumentException("scenario must be nasa or wikipedia");
        String workloadId = kind.equals("workload") ? f.get("workloadId") : null;
        if (kind.equals("workload") && (workloadId == null || store.getWorkload(workloadId) == null))
            throw new IllegalArgumentException("Choose an uploaded workload first");
        double[] budgets = null;
        if (kind.equals("sweep")) {
            String[] parts = f.getOrDefault("budgets", "1000,1500,2000,2500,3000").split(",");
            if (parts.length < 1 || parts.length > 8) throw new IllegalArgumentException("Give 1 to 8 budgets");
            budgets = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                budgets[i] = parseNum(parts[i].trim(), "budget");
                new BudgetGuard.GuardConfig(budgets[i], cfg.targetRtSec, cfg.inrPerUsd);
            }
        }
        boolean guard = "true".equals(f.get("guard"));
        if (pending.get() >= MAX_QUEUED) throw new IllegalArgumentException("The simulation queue is full; try again in a minute");

        String id = Store.newId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kind", kind);
        params.put("budgetInr", cfg.budgetInr);
        params.put("targetRt", cfg.targetRtSec);
        params.put("inrPerUsd", cfg.inrPerUsd);
        if (kind.equals("reproduce")) params.put("scenario", scenario);
        if (kind.equals("workload")) { params.put("workloadId", workloadId); params.put("guard", guard); }
        if (budgets != null) params.put("budgets", budgets);
        // an identical request within a few seconds (double-click, repeated submit) returns the run already queued
        String paramsJson = Json.write(params);
        synchronized (this) {
            if (paramsJson.equals(lastParams) && System.currentTimeMillis() - lastSubmitMillis < DUPLICATE_WINDOW_MS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", lastRunId);
                m.put("status", "duplicate");
                return m;
            }
            lastParams = paramsJson;
            lastRunId = id;
            lastSubmitMillis = System.currentTimeMillis();
        }
        store.createRun(id, kind, paramsJson, workloadId);
        StringBuffer log = new StringBuffer();
        logs.put(id, log);
        pending.incrementAndGet();
        final double[] b = budgets;
        worker.submit(() -> execute(id, kind, cfg, scenario, workloadId, guard, b, log));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("status", "queued");
        return m;
    }

    void execute(String id, String kind, BudgetGuard.GuardConfig cfg, String scenario, String workloadId, boolean guard,
                 double[] budgets, StringBuffer log) {
        PrintStream ps = new PrintStream(new OutputStream() {
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            public void write(int b) {
                line.write(b);
                if (b == '\n') { log.append(new String(line.toByteArray(), StandardCharsets.UTF_8)); line.reset(); }
            }
        }, true);
        Path out = resultsDir.resolve(id);
        try {
            store.setStatus(id, "running");
            Map<String, Object> summary = new LinkedHashMap<>();
            switch (kind) {
                case "compare": {
                    BudgetGuard.Comparison c = BudgetGuard.compare(cfg, dataDir, out, ps);
                    store.putMetrics(id, "original", c.original.metrics);
                    store.putMetrics(id, "observe", c.observe.metrics);
                    store.putMetrics(id, "guard", c.guard.metrics);
                    summary.putAll(compareSummary(cfg, c.original, c.guard));
                    summary.put("status_counts_guard", BudgetGuard.countStatus(c.guard.decisions));
                    summary.put("status_counts_original_analysed", BudgetGuard.countStatus(c.observe.decisions));
                    summary.put("observe_identical_to_original", c.original.metrics.equals(c.observe.metrics));
                    break;
                }
                case "reproduce": {
                    if (scenario.equals("nasa")) {
                        BudgetGuard.RunResult r = BudgetGuard.runChild("original", cfg, dataDir, out.resolve("original"), ps);
                        store.putMetrics(id, "original", r.metrics);
                        Map<String, Object> checks = new LinkedHashMap<>();
                        for (String f : new String[]{"Monitor_VmHistory.csv", "Monitor_SLAHistory.csv", "Monitor_EndUserHistory.csv"})
                            checks.put(f, BudgetGuard.sameFile(dataDir.resolve(f), r.dir.resolve("src/others/" + f)));
                        summary.put("scenario", "NASA, 7 days, ExperimentalSetup.main defaults (authors' unmodified code)");
                        summary.put("identical_to_authors_committed_outputs", checks);
                        summary.put("seconds", r.seconds);
                    } else {
                        BudgetGuard.Scenario sc = BudgetGuard.Scenario.wikipediaDocumented();
                        BudgetGuard.RunResult r = BudgetGuard.runChild("observe", cfg, sc, dataDir, out.resolve("observe"), null, ps);
                        store.putMetrics(id, "observe", r.metrics);
                        summary.put("scenario", sc.name + " — run through the extension's runner because the authors' main() is hard-coded to NASA");
                        summary.put("seconds", r.seconds);
                    }
                    break;
                }
                case "workload": {
                    Workload w = store.loadWorkload(workloadId);
                    List<BudgetGuard.RunResult> rs = BudgetGuard.simulateWorkload(w, cfg, guard, dataDir, out, ps);
                    for (BudgetGuard.RunResult r : rs) store.putMetrics(id, r.mode.equals("observe") ? "original_policy" : "guard", r.metrics);
                    Workload.Analysis a = w.analyse(new Workload.Params());
                    summary.put("workload", w.name);
                    summary.put("estimated_max_vms", a.summary.get("max_required_vms_observed"));
                    summary.put("estimated_cost_inr", a.summary.get("estimated_cost_observed_inr"));
                    summary.put("simulated_max_vms", rs.get(0).metrics.get("max_vms"));
                    summary.put("simulated_renting_cost_inr", Workload.round(rs.get(0).metrics.get("renting_cost_usd") * cfg.inrPerUsd, 2));
                    if (guard) summary.putAll(compareSummary(cfg, rs.get(0), rs.get(1)));
                    break;
                }
                case "sweep": {
                    BudgetGuard.sweep(budgets, cfg.targetRtSec, cfg.inrPerUsd, dataDir, out, ps);
                    List<String[]> rows = BudgetGuard.Csv.read(out.resolve("sweep.csv"));
                    store.putMetrics(id, "original", BudgetGuard.ConsoleParser.parse(new String(Files.readAllBytes(out.resolve("original/console.log")), StandardCharsets.UTF_8)));
                    for (double b : budgets) {
                        Path c = out.resolve("guard_" + (long) b).resolve("console.log");
                        store.putMetrics(id, "guard_" + (long) b, BudgetGuard.ConsoleParser.parse(new String(Files.readAllBytes(c), StandardCharsets.UTF_8)));
                    }
                    summary.put("rows", rows);
                    break;
                }
                default:
                    throw new IllegalStateException(kind);
            }
            store.finishRun(id, Json.write(summary), out.toString());
            ps.println("Finished.");
        } catch (Throwable t) {
            ps.println("FAILED: " + t.getMessage());
            try { store.failRun(id, String.valueOf(t.getMessage())); } catch (Exception ignored) { }
        } finally {
            pending.decrementAndGet();
        }
    }

    static Map<String, Object> compareSummary(BudgetGuard.GuardConfig cfg, BudgetGuard.RunResult base, BudgetGuard.RunResult g) {
        Map<String, Object> s = new LinkedHashMap<>();
        double baseCost = base.metrics.get("renting_cost_usd") * cfg.inrPerUsd, gCost = g.metrics.get("renting_cost_usd") * cfg.inrPerUsd;
        s.put("budget_inr", cfg.budgetInr);
        s.put("target_rt_s", cfg.targetRtSec);
        s.put("original_renting_inr", Workload.round(baseCost, 2));
        s.put("guard_renting_inr", Workload.round(gCost, 2));
        s.put("original_within_budget", baseCost <= cfg.budgetInr);
        s.put("guard_within_budget", gCost <= cfg.budgetInr);
        s.put("original_meets_rt_target", base.metrics.get("response_time_avg_s") <= cfg.targetRtSec);
        s.put("guard_meets_rt_target", g.metrics.get("response_time_avg_s") <= cfg.targetRtSec);
        s.put("conflict", gCost <= cfg.budgetInr && g.metrics.get("response_time_avg_s") > cfg.targetRtSec);
        return s;
    }

    /** Chart data for a finished run, read from the files each simulation wrote. */
    Map<String, Object> series(String id) throws Exception {
        Map<String, Object> r = store.getRun(id);
        if (r == null || r.get("result_dir") == null) throw new IllegalArgumentException("Run not finished");
        Path dir = Paths.get((String) r.get("result_dir"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String v : new String[]{"original", "observe", "guard", "original_policy"}) {
            Path d = dir.resolve(v);
            if (!Files.exists(d.resolve("console.log"))) continue;
            BudgetGuard.RunResult rr = BudgetGuard.collect(v, d);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hourlyVms", rr.hourlyVms);
            m.put("hourlyRt", rr.hourlyRt);
            if (!rr.decisions.isEmpty()) {
                m.put("decisionHours", BudgetGuard.div(BudgetGuard.col(rr.decisions, 0), 3600));
                m.put("committedInr", BudgetGuard.col(rr.decisions, 9));
                m.put("allowanceInr", BudgetGuard.col(rr.decisions, 10));
                m.put("statusCounts", BudgetGuard.countStatus(rr.decisions));
            }
            out.put(v, m);
        }
        return out;
    }

    Map<String, Object> decisions(String id, String variant) throws Exception {
        if (!variant.matches("[a-z_]+")) throw new IllegalArgumentException("bad variant");
        Map<String, Object> r = store.getRun(id);
        if (r == null || r.get("result_dir") == null) throw new IllegalArgumentException("Run not finished");
        Path f = Paths.get((String) r.get("result_dir")).resolve(variant).resolve("guard_decisions.csv");
        if (!Files.exists(f)) throw new IllegalArgumentException("No decision log for " + variant);
        List<String[]> rows = BudgetGuard.Csv.read(f);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("header", rows.get(0));
        m.put("rows", rows.subList(1, rows.size()));
        return m;
    }

    static Workload.Params params(Map<String, String> q) {
        Workload.Params p = new Workload.Params();
        if (q.containsKey("k")) p.k = parseNum(q.get("k"), "k");
        if (q.containsKey("lead")) p.leadBuckets = (int) parseNum(q.get("lead"), "lead");
        if (q.containsKey("util")) p.targetUtil = parseNum(q.get("util"), "util");
        if (q.containsKey("bucket")) p.bucketMin = (int) parseNum(q.get("bucket"), "bucket");
        if (q.containsKey("future")) p.futureBuckets = (int) parseNum(q.get("future"), "future");
        if (q.containsKey("inrPerUsd")) p.inrPerUsd = parseNum(q.get("inrPerUsd"), "inrPerUsd");
        return p;
    }

    static Map<String, Object> analysisJson(Workload w, Workload.Params p) {
        Workload.Analysis a = w.analyse(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name);
        m.put("summary", a.summary);
        m.put("bucketMinutes", p.bucketMin);
        m.put("rate", a.bucketRate);
        m.put("forecast", a.fcHstep);
        m.put("upper", a.upper);
        m.put("threshold", a.threshold);
        m.put("spike", a.spike);
        m.put("warned", a.warned);
        m.put("requiredVms", a.requiredVms);
        m.put("futureRate", a.futureRate);
        m.put("futureUpper", a.futureUpper);
        m.put("futureVms", a.futureVms);
        m.put("futureSpike", a.futureSpike);
        m.put("futureThreshold", a.futureThreshold);
        m.put("notes", w.notes);
        return m;
    }

    // ------------------------------------------------------------------ files

    void files(HttpExchange ex) throws IOException {
        try {
            String rel = URLDecoder.decode(ex.getRequestURI().getPath().substring("/files/".length()), "UTF-8");
            if (rel.contains("..") || rel.startsWith("/") || rel.contains("\\")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
            String runId = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
            Map<String, Object> r = store.getRun(runId);
            if (r == null || r.get("result_dir") == null) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
            Path base = Paths.get((String) r.get("result_dir")).normalize();
            Path p = base.resolve(rel.substring(runId.length()).replaceFirst("^/", "")).normalize();
            if (!p.startsWith(base) || !Files.isRegularFile(p) || p.toString().endsWith(".tset")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
            send(ex, 200, contentType(p.toString()), Files.readAllBytes(p));
        } catch (Exception e) {
            send(ex, 500, "text/plain", "error".getBytes());
        }
    }

    void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || !path.matches("/[\\w./-]+")) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
        byte[] b = null;
        if (webDir != null) {
            Path p = webDir.resolve(path.substring(1)).normalize();
            if (p.startsWith(webDir.normalize()) && Files.isRegularFile(p)) b = Files.readAllBytes(p);
        } else {
            try (InputStream in = WebApp.class.getResourceAsStream("/web" + path)) { if (in != null) b = readAll(in, MAX_BODY); }
        }
        if (b == null) { send(ex, 404, "text/plain", "not found".getBytes()); return; }
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        send(ex, 200, contentType(path), b);
    }

    static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        return "text/plain; charset=utf-8";
    }

    // ------------------------------------------------------------------ HTTP helpers

    static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    static void json(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static byte[] readBody(HttpExchange ex, int limit) throws IOException {
        return readAll(ex.getRequestBody(), limit);
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > limit) throw new IllegalArgumentException("Request too large (limit " + limit / (1024 * 1024) + " MB)");
        }
        return bo.toByteArray();
    }

    static Map<String, String> query(String raw) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    static Map<String, String> form(byte[] body) throws IOException {
        return query(new String(body, StandardCharsets.UTF_8));
    }

    static double num(Map<String, String> f, String k, double def) {
        String v = f.get(k);
        return v == null || v.trim().isEmpty() ? def : parseNum(v.trim(), k);
    }

    static double parseNum(String v, String name) {
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number (got '" + v + "')");
        }
    }
}
