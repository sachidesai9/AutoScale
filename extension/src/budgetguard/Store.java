package budgetguard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for the web application: simulation runs and their metrics, and uploaded workloads.
 *
 * Default: SQLite file (DATA_DIR/autoscalesim.db) — one server, low write volume, no administration or cost.
 * Optional: PostgreSQL (e.g. Amazon RDS) by setting DATABASE_URL=jdbc:postgresql://host:5432/db plus
 * DB_USER / DB_PASSWORD. The SQL below is portable between the two.
 */
public final class Store implements AutoCloseable {

    static final String[] SCHEMA = {
            "CREATE TABLE IF NOT EXISTS workloads ("
                    + " id VARCHAR(40) PRIMARY KEY, name VARCHAR(255) NOT NULL, format VARCHAR(40) NOT NULL,"
                    + " uploaded_at VARCHAR(40) NOT NULL, minutes INTEGER NOT NULL, total_requests BIGINT NOT NULL,"
                    + " peak_per_min DOUBLE PRECISION NOT NULL, mean_per_min DOUBLE PRECISION NOT NULL,"
                    + " notes TEXT, csv TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS runs ("
                    + " id VARCHAR(40) PRIMARY KEY, kind VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL,"
                    + " created_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40), params TEXT NOT NULL,"
                    + " summary TEXT, result_dir VARCHAR(500), error TEXT, workload_id VARCHAR(40))",
            "CREATE TABLE IF NOT EXISTS run_metrics ("
                    + " run_id VARCHAR(40) NOT NULL, variant VARCHAR(40) NOT NULL, metric VARCHAR(60) NOT NULL,"
                    + " value DOUBLE PRECISION, PRIMARY KEY (run_id, variant, metric))",
            "CREATE INDEX IF NOT EXISTS idx_runs_created ON runs (created_at)",
    };

    final Connection conn;
    public final String kind;

    public Store(String jdbcUrl, String user, String password) throws SQLException {
        this.conn = user == null ? DriverManager.getConnection(jdbcUrl) : DriverManager.getConnection(jdbcUrl, user, password);
        this.kind = jdbcUrl.startsWith("jdbc:postgresql") ? "PostgreSQL" : jdbcUrl.startsWith("jdbc:sqlite") ? "SQLite" : "JDBC";
        try (Statement st = conn.createStatement()) {
            for (String sql : SCHEMA) st.execute(sql);
        }
    }

    /** DATABASE_URL (PostgreSQL) if set, otherwise a SQLite file in dataDir. */
    public static Store open(Path dataDir) throws Exception {
        String url = System.getenv("DATABASE_URL");
        if (url != null && !url.trim().isEmpty())
            return new Store(url.trim(), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
        Files.createDirectories(dataDir);
        return new Store("jdbc:sqlite:" + dataDir.resolve("autoscalesim.db").toAbsolutePath(), null, null);
    }

    public static String newId() { return UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis() % 100000; }

    static String now() { return Instant.now().toString(); }

    // ---------------------------------------------------------------- workloads

    public synchronized String addWorkload(Workload w, String csv) throws SQLException {
        String id = newId();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workloads (id, name, format, uploaded_at, minutes, total_requests, peak_per_min, mean_per_min, notes, csv)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, w.name);
            ps.setString(3, w.format);
            ps.setString(4, now());
            ps.setInt(5, w.perMinute.length);
            ps.setLong(6, w.total());
            ps.setDouble(7, w.peak());
            ps.setDouble(8, w.mean());
            ps.setString(9, String.join(" ", w.notes));
            ps.setString(10, csv);
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized List<Map<String, Object>> listWorkloads() throws SQLException {
        return query("SELECT id, name, format, uploaded_at, minutes, total_requests, peak_per_min, mean_per_min, notes"
                + " FROM workloads ORDER BY uploaded_at DESC");
    }

    public synchronized Map<String, Object> getWorkload(String id) throws SQLException {
        List<Map<String, Object>> r = query("SELECT * FROM workloads WHERE id = ?", id);
        return r.isEmpty() ? null : r.get(0);
    }

    public synchronized Workload loadWorkload(String id) throws SQLException {
        Map<String, Object> m = getWorkload(id);
        if (m == null) return null;
        return Workload.parse((String) m.get("name"), (String) m.get("csv"));
    }

    // ---------------------------------------------------------------- runs

    public synchronized void createRun(String id, String kind, String paramsJson, String workloadId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO runs (id, kind, status, created_at, params, workload_id) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, kind);
            ps.setString(3, "queued");
            ps.setString(4, now());
            ps.setString(5, paramsJson);
            ps.setString(6, workloadId);
            ps.executeUpdate();
        }
    }

    public synchronized void setStatus(String id, String status) throws SQLException {
        update("UPDATE runs SET status = ? WHERE id = ?", status, id);
    }

    public synchronized void finishRun(String id, String summaryJson, String resultDir) throws SQLException {
        update("UPDATE runs SET status = 'done', finished_at = ?, summary = ?, result_dir = ? WHERE id = ?", now(), summaryJson, resultDir, id);
    }

    public synchronized void failRun(String id, String error) throws SQLException {
        update("UPDATE runs SET status = 'failed', finished_at = ?, error = ? WHERE id = ?", now(), error, id);
    }

    /** Runs left queued/running by a previous process cannot resume; mark them failed on start-up. */
    public synchronized int failInterrupted() throws SQLException {
        return update("UPDATE runs SET status = 'failed', error = 'Interrupted: the server restarted before this run finished'"
                + " WHERE status IN ('queued', 'running')");
    }

    public synchronized void putMetrics(String runId, String variant, Map<String, Double> metrics) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM run_metrics WHERE run_id = ? AND variant = ?")) {
            del.setString(1, runId);
            del.setString(2, variant);
            del.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO run_metrics (run_id, variant, metric, value) VALUES (?,?,?,?)")) {
            for (Map.Entry<String, Double> e : metrics.entrySet()) {
                ps.setString(1, runId);
                ps.setString(2, variant);
                ps.setString(3, e.getKey());
                if (e.getValue() == null || e.getValue().isNaN()) ps.setNull(4, java.sql.Types.DOUBLE);
                else ps.setDouble(4, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** variant -> metric -> value */
    public synchronized Map<String, Map<String, Double>> getMetrics(String runId) throws SQLException {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : query("SELECT variant, metric, value FROM run_metrics WHERE run_id = ? ORDER BY variant, metric", runId)) {
            Object v = r.get("value");
            out.computeIfAbsent((String) r.get("variant"), k -> new LinkedHashMap<>())
                    .put((String) r.get("metric"), v == null ? null : ((Number) v).doubleValue());
        }
        return out;
    }

    public synchronized Map<String, Object> getRun(String id) throws SQLException {
        List<Map<String, Object>> r = query("SELECT * FROM runs WHERE id = ?", id);
        return r.isEmpty() ? null : r.get(0);
    }

    public synchronized List<Map<String, Object>> listRuns(int limit) throws SQLException {
        return query("SELECT id, kind, status, created_at, finished_at, params, summary, error, workload_id FROM runs"
                + " ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(500, limit)));
    }

    public synchronized long count(String table) throws SQLException {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException(table);
        return ((Number) query("SELECT COUNT(*) AS n FROM " + table).get(0).get("n")).longValue();
    }

    // ---------------------------------------------------------------- helpers

    int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) row.put(md.getColumnLabel(c).toLowerCase(), rs.getObject(c));
                    out.add(row);
                }
                return out;
            }
        }
    }

    @Override
    public void close() throws SQLException { conn.close(); }
}
