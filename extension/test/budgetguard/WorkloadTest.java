package budgetguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neuroph.core.data.DataSet;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Workload upload, conversion to the original trace format, forecasting and spike warnings. */
class WorkloadTest {

    static String perMinuteCsv(double[] v) {
        StringBuilder s = new StringBuilder("minute,requests\n");
        for (int i = 0; i < v.length; i++) s.append(i).append(',').append(v[i]).append('\n');
        return s.toString();
    }

    /** Synthetic series with a daily cycle (for tests only). */
    static double[] daily(int days) {
        double[] v = new double[days * 1440];
        for (int i = 0; i < v.length; i++) v[i] = Math.round(50 + 30 * Math.sin(2 * Math.PI * (i % 1440) / 1440.0));
        return v;
    }

    @Test void parsesPerMinuteCounts() {
        Workload w = Workload.parse("x.csv", perMinuteCsv(daily(1)));
        assertEquals("per-minute counts", w.format);
        assertEquals(1440, w.perMinute.length);
        assertEquals(80, w.peak());
    }

    @Test void acceptsAlternativeHeadersAndSemicolons() {
        StringBuilder s = new StringBuilder("﻿time;Hits\n");
        for (int i = 0; i < 200; i++) s.append(i).append(';').append(10).append('\n');
        Workload w = Workload.parse("x.csv", s.toString());
        assertEquals(2000, w.total());
    }

    @Test void parsesRequestLogWithIsoAndEpochTimestamps() {
        StringBuilder s = new StringBuilder("timestamp,path\n");
        for (int m = 0; m < 150; m++) for (int k = 0; k < 3; k++) s.append(1700000000L + m * 60L + k).append(",/\n");
        Workload w = Workload.parse("log.csv", s.toString());
        assertEquals("request log", w.format);
        assertEquals(150, w.perMinute.length);
        assertEquals(450, w.total());
        StringBuilder iso = new StringBuilder("timestamp\n");
        for (int m = 0; m < 130; m++) iso.append(java.time.Instant.ofEpochSecond(1767607200L + m * 60L)).append('\n');
        assertEquals(130, Workload.parse("iso.csv", iso.toString()).perMinute.length);
    }

    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", ""));
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", "foo,bar\n1,2\n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", "minute,requests\n0,5\n1,abc\n"));
        assertTrue(e.getMessage().contains("Line 3"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", perMinuteCsv(new double[50])));          // too short
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", perMinuteCsv(new double[7 * 1440 + 1]))); // too long
        double[] neg = daily(1); neg[5] = -1;
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", perMinuteCsv(neg)));
        double[] big = daily(1); big[5] = 5000;
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", perMinuteCsv(big)));
        assertThrows(IllegalArgumentException.class, () -> Workload.parse("x", perMinuteCsv(new double[200])));          // all zero
    }

    @Test void roundsFractionalCountsWithNote() {
        double[] v = daily(1); v[0] = 10.4;
        Workload w = Workload.parse("x", perMinuteCsv(v));
        assertEquals(10, w.perMinute[0]);
        assertFalse(w.notes.isEmpty());
    }

    @Test void tsetHasOriginalLayoutAndCounts(@TempDir Path tmp) {
        double[] v = daily(1);
        v[10] = 300;                                         // wider than the NASA row width of 200
        Workload w = Workload.parse("x", perMinuteCsv(v));
        Path f = tmp.resolve("w.tset");
        w.writeTset(f);
        DataSet d = DataSet.load(f.toString());
        assertEquals(1440, d.size());
        assertEquals(5, d.getInputSize());
        assertEquals(300, d.getOutputSize());
        for (int m = 0; m < 1440; m++) {
            int n = (int) d.getRowAt(m).getInput()[4];
            assertEquals((int) v[m], n);
            double[] out = d.getRowAt(m).getDesiredOutput();
            for (int i = 0; i < n; i++) {
                assertTrue(out[i] >= 0 && out[i] <= 59, "arrival second in [0,59]");
                if (i > 0) assertTrue(out[i] >= out[i - 1], "sorted like the original trace");
            }
        }
    }

    @Test void tsetIsDeterministic(@TempDir Path tmp) throws Exception {
        Workload w = Workload.parse("x", perMinuteCsv(daily(1)));
        w.writeTset(tmp.resolve("a.tset"));
        w.writeTset(tmp.resolve("b.tset"));
        DataSet a = DataSet.load(tmp.resolve("a.tset").toString()), b = DataSet.load(tmp.resolve("b.tset").toString());
        for (int m = 0; m < 1440; m += 37) assertArrayEquals(a.getRowAt(m).getDesiredOutput(), b.getRowAt(m).getDesiredOutput());
    }

    @Test void capacityComesFromSimulatorConstants() {
        // t2.medium: 2 PEs x 2500 MIPS; request 5000 MI on each of 2 PEs -> 2 s per request -> 30 per minute
        assertEquals(30.0, new Workload.Params().vmCapacityPerMinute(), 1e-9);
    }

    @Test void seasonalForecastBeatsNaiveOnDailyPattern() {
        Workload w = Workload.parse("x", perMinuteCsv(daily(4)));
        Workload.Analysis a = w.analyse(new Workload.Params());
        assertTrue(a.model.season > 0, "uses a daily season with 4 days of data");
        double mae = (Double) a.summary.get("mae_model"), naive = (Double) a.summary.get("mae_naive_last_value");
        assertTrue(mae < naive, "Holt-Winters " + mae + " should beat last-value " + naive);
    }

    @Test void detectsInjectedSpike() {
        double[] v = daily(3);
        for (int i = 2 * 1440 + 600; i < 2 * 1440 + 640; i++) v[i] = v[i] * 4;
        Workload.Analysis a = Workload.parse("x", perMinuteCsv(v)).analyse(new Workload.Params());
        int spikeBucket = (2 * 1440 + 610) / 10;
        assertTrue(a.spike[spikeBucket], "injected x4 burst is flagged as a spike");
        assertTrue((Integer) a.summary.get("spikes_detected") >= 1);
    }

    @Test void requiredVmsFollowDemand() {
        Workload.Analysis a = Workload.parse("x", perMinuteCsv(daily(1))).analyse(new Workload.Params());
        // peak 80 req/min at 70 % of 30 req/min -> ceil(80/21) = 4 VMs
        assertEquals(4, (Integer) a.summary.get("max_required_vms_observed"));
    }

    @Test void futureHorizonNeverLongerThanHistory() {
        Workload.Analysis a = Workload.parse("x", perMinuteCsv(new double[180]) .replace(",0.0", ",5.0")).analyse(new Workload.Params());
        assertEquals(18, a.futureRate.length);
        assertNotNull(a.summary.get("note"));
    }

    @Test void paramsValidated() {
        Workload.Params p = new Workload.Params();
        p.k = 0.5;
        assertThrows(IllegalArgumentException.class, p::validate);
        Workload.Params q = new Workload.Params();
        q.targetUtil = 2;
        assertThrows(IllegalArgumentException.class, q::validate);
    }
}
