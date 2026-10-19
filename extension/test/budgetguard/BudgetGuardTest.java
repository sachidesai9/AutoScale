package budgetguard;

import budgetguard.BudgetGuard.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests of the budget guard: validation, billing, pacing, decision rule, explanations, parsing, report. */
class BudgetGuardTest {

    static final int UP = Policy.UP, DOWN = Policy.DOWN, NO = Policy.NOTHING;

    @Test void validConfigAccepted() { assertDoesNotThrow(() -> new GuardConfig(2000, 4, 85)); }

    @Test void invalidBudgetsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(0, 4, 85));
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(-5, 4, 85));
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(Double.NaN, 4, 85));
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(Double.POSITIVE_INFINITY, 4, 85));
    }

    @Test void invalidTargetAndRateRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(2000, 0, 85));
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(2000, -1, 85));
        assertThrows(IllegalArgumentException.class, () -> new GuardConfig(2000, 4, -1));
    }

    @Test void formNumbersValidated() {
        assertThrows(IllegalArgumentException.class, () -> WebApp.parseNum("abc", "budget"));
        assertThrows(IllegalArgumentException.class, () -> WebApp.parseNum("NaN", "budget"));
        assertEquals(12.5, WebApp.parseNum("12.5", "budget"));
    }

    @Test void budgetConvertedToUsd() { assertEquals(10, new GuardConfig(850, 4, 85).budgetUsd(), 1e-9); }

    @Test void billingMatchesOriginalCeilRule() {
        assertEquals(0.08, Policy.vmChargeUsd(false, 0, 0, 5400, 0.04), 1e-12);   // 90 min -> 2 hours
        assertEquals(0.04, Policy.vmChargeUsd(false, 0, 100, 100, 0.04), 1e-12);  // just requested -> 1 hour
        assertEquals(0.04, Policy.vmChargeUsd(false, 0, 0, 3600, 0.04), 1e-12);   // exactly 1 hour
        assertEquals(0.12, Policy.vmChargeUsd(true, 0.12, 0, 99999, 0.04));       // destroyed: final bill
    }

    @Test void pacing() {
        double T = 7 * 24 * 3600.0;
        assertEquals(1.0, Policy.allowanceUsd(168, 0, T, 0), 1e-9);
        assertEquals(168, Policy.allowanceUsd(168, T, T, 0));
        assertEquals(167.96, Policy.allowanceUsd(168, T, T, 0.04), 1e-9);
    }

    @Test void decisionRule() {
        Outcome o = Policy.decide(UP, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        assertEquals(UP, o.guardedDecision); assertEquals(Status.OK, o.status);
        o = Policy.decide(UP, 1.0, 0.04, 2.0, 3, 1, 5.0, 4.0);
        assertEquals(UP, o.guardedDecision); assertEquals(Status.PERF_MISS, o.status);
        assertEquals(UP, Policy.decide(UP, 1.96, 0.04, 2.0, 3, 1, 3.0, 4.0).guardedDecision, "limit is inclusive");
        o = Policy.decide(UP, 1.99, 0.04, 2.0, 3, 1, 3.0, 4.0);
        assertEquals(NO, o.guardedDecision); assertEquals(Status.BUDGET_HOLD, o.status);
        o = Policy.decide(UP, 1.99, 0.04, 2.0, 3, 1, 6.0, 4.0);
        assertEquals(NO, o.guardedDecision); assertEquals(Status.CONFLICT, o.status);
        o = Policy.decide(DOWN, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        assertEquals(DOWN, o.guardedDecision); assertEquals(Status.OK, o.status);
        o = Policy.decide(NO, 2.5, 0.04, 2.0, 3, 1, 3.0, 4.0);
        assertEquals(DOWN, o.guardedDecision); assertEquals(Status.BUDGET_RECOVERY, o.status); assertFalse(o.budgetOk);
        o = Policy.decide(UP, 2.5, 0.04, 2.0, 3, 1, 6.0, 4.0);
        assertEquals(DOWN, o.guardedDecision); assertEquals(Status.CONFLICT, o.status);
        o = Policy.decide(UP, 2.5, 0.04, 2.0, 1, 1, 6.0, 4.0);
        assertEquals(NO, o.guardedDecision); assertEquals(Status.INFEASIBLE, o.status);
        o = Policy.decide(NO, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        assertEquals(NO, o.guardedDecision); assertEquals(Status.OK, o.status);
    }

    static DecisionRecord conflictRecord(GuardConfig cfg) {
        DecisionRecord r = new DecisionRecord();
        r.originalDecision = UP; r.delayTime = 1.4; r.responseTime = 4.6; r.activeVms = 3; r.committedUsd = 1.99; r.allowanceUsd = 2.0; r.priceUsd = 0.04;
        Outcome oc = Policy.decide(UP, r.committedUsd, r.priceUsd, r.allowanceUsd, r.activeVms, 1, r.responseTime, cfg.targetRtSec);
        r.guardedDecision = oc.guardedDecision; r.status = oc.status; r.budgetOk = oc.budgetOk; r.perfOk = oc.perfOk;
        return r;
    }

    @Test void explanationsUseRecordedNumbers() {
        GuardConfig cfg = new GuardConfig(2000, 4, 85);
        DecisionRecord r = conflictRecord(cfg);
        String e = BudgetGuard.explain(r, cfg, 1.0, 0.2, 1, true);
        assertTrue(e.contains("1.400 s") && e.contains("SCALE_UP"), e);
        assertTrue(e.contains("₹172.55") && e.contains("₹170.00"), e);
        assertTrue(e.contains("misses the 4.00 s target") && e.contains("WARNING"), e);
        assertTrue(BudgetGuard.explain(r, cfg, 1.0, 0.2, 1, false).contains("Analysis only"));
        r.status = Status.INFEASIBLE; r.budgetOk = false; r.activeVms = 1;
        assertTrue(BudgetGuard.explain(r, cfg, 1.0, 0.2, 1, true).contains("no feasible configuration"));
    }

    @Test void explanationsForResourceAwareRule() {
        GuardConfig cfg = new GuardConfig(2000, 4, 85);
        DecisionRecord r = conflictRecord(cfg);
        r.cpuUtil = 85;
        String e = BudgetGuard.explain(r, cfg, "RESOURCE_AWARE", 70, 40, 1.0, 0.2, 1, true);
        assertTrue(e.contains("CPU utilisation 85.0%") && e.contains("70%"), e);
    }

    static final String CONSOLE = "Total User requests : 567783.0\nCPU Utilization (%):35.799 Avg.   36.335 SD\nResponse Time (Sec.): 3.986 Avg.   4.464 SD\n"
            + "Delay Time (Sec.): 1.989 Avg.   4.462 SD\nCloudlet Canceled: 68\nCloudlet Failured: 9264\nSLA Violation: 29.617%\nMax used Vms: 8\n"
            + "Scale Up Decisions: 306\nScale Down Decisions: 304\nContradictory Scaling Decisions: 279\nProvisioned On-Demand Vm:306\n"
            + "De-Provisioned On-Demand Vm:304\n\u001B[31mCOST:...\nRenting Cost: $ 29.84\nSLA Penalty: $ 11.24\nTotal Cost : $ \u001B[31m......   41.08    .......";

    @Test void parsesOriginalEndOfRunReport() {
        Map<String, Double> m = ConsoleParser.parse(CONSOLE);
        assertEquals(29.84, m.get("renting_cost_usd"));
        assertEquals(11.24, m.get("sla_penalty_usd"));
        assertEquals(41.08, m.get("total_cost_usd"));
        assertEquals(3.986, m.get("response_time_avg_s"));
        assertEquals(29.617, m.get("sla_violation_pct"));
        assertEquals(8, m.get("max_vms"));
        assertEquals(ConsoleParser.PATTERNS.length, m.size());
    }

    @Test void csvRoundTrip(@TempDir Path tmp) throws Exception {
        Csv.write(tmp.resolve("t.csv"), Arrays.asList(new String[]{"a", "b"}, new String[]{"x, \"y\"", "₹1"}));
        List<String[]> back = Csv.read(tmp.resolve("t.csv"));
        assertEquals("x, \"y\"", back.get(1)[0]);
        assertEquals("₹1", back.get(1)[1]);
    }

    static RunResult fake(String mode, double rentUsd, double rt) {
        RunResult r = new RunResult();
        r.mode = mode;
        r.metrics.put("renting_cost_usd", rentUsd); r.metrics.put("sla_penalty_usd", 1.0); r.metrics.put("total_cost_usd", rentUsd + 1);
        r.metrics.put("response_time_avg_s", rt); r.metrics.put("max_vms", 8.0);
        r.hourlyVms = new double[]{2, 3, 4}; r.hourlyRt = new double[]{3, 4, 5};
        return r;
    }

    @Test void reportGeneration(@TempDir Path tmp) throws Exception {
        Comparison c = new Comparison();
        c.cfg = new GuardConfig(2000, 4, 85); c.dir = tmp;
        c.original = fake("original", 29.84, 3.986); c.observe = fake("observe", 29.84, 3.986); c.guard = fake("guard", 20.0, 4.5);
        c.observe.decisions.add(BudgetGuard.DECISION_HEADER); c.guard.decisions.add(BudgetGuard.DECISION_HEADER);
        String[] row = {"600", "SATURDAY:00:10:00", "50", "1.2", "3.2", "2", "SCALE_UP", "DO_NOTHING", "true", "100", "120", "true", "true", "BUDGET_HOLD", "text, with comma"};
        c.observe.decisions.add(row); c.guard.decisions.add(row);
        Report.writeComparison(c);
        String html = new String(Files.readAllBytes(tmp.resolve("report.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("1. Original AutoScaleSim") && html.contains("2. Original, analysed by extension") && html.contains("3. Budget-guarded policy"));
        assertTrue(html.contains("cannot both be satisfied"));
        assertTrue(html.contains("text, with comma") && html.contains("BUDGET_HOLD"));
    }

    @Test void scenarioDefaultsMatchAuthorsMain() {
        Scenario s = Scenario.nasaDefault();
        assertTrue(s.isNasaDefault());
        assertEquals(7 * 1440, s.simMinutes);
        assertEquals("SLA_AWARE", s.rule);
        assertEquals(40, s.maxVm);
        assertEquals(1.0, s.dtUp);
        Scenario w = Scenario.wikipediaDocumented();
        assertFalse(w.isNasaDefault());
        assertEquals(4 * 60, w.simMinutes);
        assertEquals(Scenario.fromProperties(w.toProperties()).toProperties(), w.toProperties());
    }

    @Test void originalModeRefusesNonDefaultScenarios(@TempDir Path tmp) {
        Scenario w = Scenario.wikipediaDocumented();
        assertThrows(IllegalArgumentException.class,
                () -> BudgetGuard.runChild("original", new GuardConfig(2000, 4, 85), w, tmp, tmp.resolve("r"), null, null));
    }

    @Test void jsonWriter() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", 1.5); m.put("b", "x\"<y"); m.put("c", new int[]{1, 2}); m.put("d", Double.NaN); m.put("e", null); m.put("f", new boolean[]{true, false});
        assertEquals("{\"a\":1.5,\"b\":\"x\\\"\\u003cy\",\"c\":[1,2],\"d\":null,\"e\":null,\"f\":[1,0]}", Json.write(m));
    }
}
