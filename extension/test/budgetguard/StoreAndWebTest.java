package budgetguard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Database layer (SQLite, and PostgreSQL when TEST_POSTGRES_URL is set) and the web API without simulations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreAndWebTest {

    Path tmp;
    WebApp app;
    Store store;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    static String csv(int minutes) {
        StringBuilder s = new StringBuilder("minute,requests\n");
        for (int i = 0; i < minutes; i++) s.append(i).append(',').append(20 + i % 7).append('\n');
        return s.toString();
    }

    @BeforeAll void start() throws Exception {
        tmp = Files.createTempDirectory("autoscalesim-web");
        store = new Store("jdbc:sqlite:" + tmp.resolve("t.db"), null, null);
        Path web = Paths.get("extension/web");
        app = new WebApp(store, Paths.get("src/others"), tmp.resolve("results"), Files.isDirectory(web) ? web : null);
        app.start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterAll void stop() throws Exception { app.stop(); store.close(); }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String type) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------------------------------------------------------------- database

    void exerciseStore(Store s) throws Exception {
        Workload w = Workload.parse("t.csv", csv(200));
        String wid = s.addWorkload(w, WebApp.normalisedCsv(w));
        assertEquals(200, ((Number) s.getWorkload(wid).get("minutes")).intValue());
        assertEquals(w.total(), s.loadWorkload(wid).total(), "CSV stored in the database round-trips");

        String id = Store.newId();
        s.createRun(id, "compare", "{\"budgetInr\":2000}", null);
        assertEquals("queued", s.getRun(id).get("status"));
        s.setStatus(id, "running");
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("renting_cost_usd", 29.84);
        m.put("response_time_avg_s", 3.986);
        m.put("missing", Double.NaN);
        s.putMetrics(id, "original", m);
        s.putMetrics(id, "original", m);                      // idempotent replace
        assertEquals(29.84, s.getMetrics(id).get("original").get("renting_cost_usd"));
        assertNull(s.getMetrics(id).get("original").get("missing"));
        s.finishRun(id, "{\"ok\":true}", "/tmp/x");
        assertEquals("done", s.getRun(id).get("status"));

        String id2 = Store.newId();
        s.createRun(id2, "sweep", "{}", null);
        assertTrue(s.failInterrupted() >= 1);
        assertEquals("failed", s.getRun(id2).get("status"));
        assertTrue(s.listRuns(10).size() >= 2);
    }

    @Test void sqliteStore() throws Exception {
        try (Store s = new Store("jdbc:sqlite:" + tmp.resolve("s.db"), null, null)) {
            assertEquals("SQLite", s.kind);
            exerciseStore(s);
        }
        try (Store s = new Store("jdbc:sqlite:" + tmp.resolve("s.db"), null, null)) {
            assertEquals(1, s.count("workloads"), "data persists across connections");
        }
    }

    @Test void postgresStore() throws Exception {
        String url = System.getenv("TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isEmpty(), "TEST_POSTGRES_URL not set");
        try (Store s = new Store(url, System.getenv("TEST_POSTGRES_USER"), System.getenv("TEST_POSTGRES_PASSWORD"))) {
            assertEquals("PostgreSQL", s.kind);
            exerciseStore(s);
        }
    }

    // ---------------------------------------------------------------- web API

    @Test void healthAndInfo() throws Exception {
        HttpResponse<String> r = get("/api/health");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"status\":\"ok\"") && r.body().contains("SQLite"));
        assertTrue(get("/api/info").body().contains("10.1016/j.simpat.2020.102245"));
    }

    @Test void uploadAnalyseAndDownload() throws Exception {
        HttpResponse<String> up = post("/api/workloads?name=demo.csv", csv(300), "text/csv");
        assertEquals(201, up.statusCode(), up.body());
        String id = up.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> an = get("/api/workloads/" + id + "/analysis?k=2.5&lead=3");
        assertEquals(200, an.statusCode(), an.body());
        assertTrue(an.body().contains("\"futureRate\"") && an.body().contains("\"mae_model\""));
        HttpResponse<String> dl = get("/api/workloads/" + id + "/csv");
        assertEquals(200, dl.statusCode());
        assertTrue(dl.body().startsWith("minute,requests"));
        assertTrue(get("/api/workloads").body().contains("demo.csv"));
    }

    @Test void rejectsInvalidUploadsAndParameters() throws Exception {
        assertEquals(400, post("/api/workloads?name=bad.csv", "minute,requests\n0,abc\n", "text/csv").statusCode());
        assertEquals(400, post("/api/runs", "kind=compare&budgetInr=-5", "application/x-www-form-urlencoded").statusCode());
        assertEquals(400, post("/api/runs", "kind=compare&targetRt=abc", "application/x-www-form-urlencoded").statusCode());
        assertEquals(400, post("/api/runs", "kind=format-disk", "application/x-www-form-urlencoded").statusCode());
        assertEquals(400, post("/api/runs", "kind=workload&workloadId=nope", "application/x-www-form-urlencoded").statusCode());
        assertEquals(400, post("/api/runs", "kind=sweep&budgets=1,2,3,4,5,6,7,8,9", "application/x-www-form-urlencoded").statusCode());
        HttpResponse<String> an = get("/api/workloads/missing/analysis");
        assertEquals(404, an.statusCode());
    }

    @Test void blocksPathTraversalAndUnknownPaths() throws Exception {
        assertEquals(404, get("/files/../../etc/passwd").statusCode());
        assertEquals(404, get("/files/nope/report.html").statusCode());
        assertEquals(404, get("/api/nothing").statusCode());
        assertEquals(404, get("/..%2f..%2fetc/passwd").statusCode());
    }

    @Test void servesDashboard() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("AutoScaleSim Lab"));
        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/vendor/chart.umd.min.js").statusCode());
    }

    @Test void runsListIsJson() throws Exception {
        HttpResponse<String> r = get("/api/runs");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().startsWith("["));
    }

    @Test void seedsSampleWorkloadsWhenEmpty() throws Exception {
        List<Map<String, Object>> ws = store.listWorkloads();
        assumeTrue(Files.isDirectory(Paths.get("samples")), "samples folder not present");
        assertTrue(ws.stream().anyMatch(w -> String.valueOf(w.get("name")).startsWith("sample: ")));
    }

    @Test void duplicateSubmissionReturnsTheSameRun() throws Exception {
        String body = "kind=reproduce&scenario=nasa";
        HttpResponse<String> first = post("/api/runs", body, "application/x-www-form-urlencoded");
        HttpResponse<String> second = post("/api/runs", body, "application/x-www-form-urlencoded");
        assertEquals(202, first.statusCode(), first.body());
        String id1 = first.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"), id2 = second.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertEquals(id1, id2, "a double-click must not start two simulations");
        assertTrue(second.body().contains("duplicate"));
    }

    @Test void historyCommandDeletesOnlyInsideItsOwnResultsFolder() throws Exception {
        Path data = Files.createTempDirectory("hist-data"), outside = Files.createTempDirectory("hist-outside");
        Path inside = data.resolve("results").resolve("r1");
        Files.createDirectories(inside);
        Files.write(inside.resolve("x.txt"), "x".getBytes());
        Files.write(outside.resolve("keep.txt"), "keep".getBytes());
        try (Store s = Store.open(data)) {
            s.createRun("r1", "compare", "{}", null); s.finishRun("r1", "{}", inside.toString());
            s.createRun("r2", "compare", "{}", null); s.finishRun("r2", "{}", outside.toString());
        }
        BudgetGuard.history(new String[]{"history", "clear-runs"}, java.util.Map.of("data-dir", data.toString()));
        assertFalse(Files.exists(inside), "run folder inside DATA_DIR/results is removed");
        assertTrue(Files.exists(outside.resolve("keep.txt")), "a folder outside DATA_DIR/results is never deleted");
        try (Store s = Store.open(data)) { assertEquals(0, s.count("runs")); }
    }
}
