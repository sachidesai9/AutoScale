package budgetguard;

import budgetguard.BudgetGuard.GuardConfig;
import budgetguard.BudgetGuard.RunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs real AutoScaleSim simulations (about 1-2 minutes in total). Tagged "integration".
 * Requires the authors' dataset in src/others (run from the repository root).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

    static final Path DATA = Paths.get("src/others").toAbsolutePath();
    static final String[] CSVS = {"Monitor_VmHistory.csv", "Monitor_SLAHistory.csv", "Monitor_EndUserHistory.csv"};
    Path out;
    RunResult original, observe;
    final GuardConfig tight = new GuardConfig(1500, 4.0, BudgetGuard.DEFAULT_INR_PER_USD);

    @BeforeAll void runBaseline() throws Exception {
        out = Files.createTempDirectory("autoscalesim-it");
        original = BudgetGuard.runChild("original", tight, DATA, out.resolve("original"), System.out);
        observe = BudgetGuard.runChild("observe", tight, DATA, out.resolve("observe"), System.out);
    }

    @Test void originalReproducesAuthorsCommittedOutputs() throws Exception {
        for (String f : CSVS)
            assertTrue(BudgetGuard.sameFile(DATA.resolve(f), original.dir.resolve("src/others/" + f)), f + " differs from the authors' file");
        assertEquals(567783.0, original.metrics.get("total_requests"));
        assertEquals(29.84, original.metrics.get("renting_cost_usd"));
    }

    @Test void observeModeDoesNotChangeTheSimulation() throws Exception {
        for (String f : CSVS)
            assertTrue(BudgetGuard.sameFile(original.dir.resolve("src/others/" + f), observe.dir.resolve("src/others/" + f)), f);
        assertEquals(original.metrics, observe.metrics);
        assertEquals(1008, observe.decisions.size() - 1, "one decision per 10-minute interval over 7 days");
    }

    @Test void costEstimateConsistentWithOriginalBill() {
        double last = Double.parseDouble(observe.decisions.get(observe.decisions.size() - 1)[9]);
        double printed = original.metrics.get("renting_cost_usd") * BudgetGuard.DEFAULT_INR_PER_USD;
        assertEquals(printed, last, 0.02 * printed);
    }

    @Test void unlimitedBudgetChangesNothing() throws Exception {
        RunResult loose = BudgetGuard.runChild("guard", new GuardConfig(1e9, 1e9, BudgetGuard.DEFAULT_INR_PER_USD), DATA, out.resolve("unlimited"), System.out);
        assertTrue(BudgetGuard.sameFile(original.dir.resolve("src/others/Monitor_VmHistory.csv"), loose.dir.resolve("src/others/Monitor_VmHistory.csv")));
    }

    @Test void tightBudgetIsEnforced() throws Exception {
        RunResult g = BudgetGuard.runChild("guard", tight, DATA, out.resolve("guard1500"), System.out);
        double rc = g.metrics.get("renting_cost_usd") * BudgetGuard.DEFAULT_INR_PER_USD;
        Map<String, Integer> cnt = BudgetGuard.countStatus(g.decisions);
        assertTrue(rc <= 1500 || cnt.getOrDefault("INFEASIBLE", 0) > 0, "renting ₹" + rc);
        assertEquals(original.metrics.get("total_requests"), g.metrics.get("total_requests"), "same workload");
    }

    @Test void workloadPipelineIsExactForTheOriginalTrace() throws Exception {
        // copy the first 7 days of the authors' trace row by row and replay it through the upload pipeline
        DataSet d = DataSet.load(DATA.resolve("Dataset_NASA_July.tset").toString());
        DataSet copy = new DataSet(5, d.getOutputSize());
        for (int i = 0; i < 7 * 1440; i++)
            copy.addRow(new DataSetRow(d.getRowAt(i).getInput().clone(), d.getRowAt(i).getDesiredOutput().clone()));
        Path tset = out.resolve("copy.tset");
        copy.save(tset.toString());
        BudgetGuard.Scenario sc = BudgetGuard.Scenario.nasaDefault();
        RunResult r = BudgetGuard.runChild("observe", tight, sc, DATA, out.resolve("exactcopy"), tset, System.out);
        assertTrue(BudgetGuard.sameFile(original.dir.resolve("src/others/Monitor_VmHistory.csv"), r.dir.resolve("src/others/Monitor_VmHistory.csv")));
    }

    @Test void uploadedWorkloadIsReplayedCompletely() throws Exception {
        Path csv = Paths.get("samples/synthetic_flash_crowd_3days.csv");
        Workload w = Workload.parse("flash", new String(Files.readAllBytes(csv)));
        RunResult r = BudgetGuard.simulateWorkload(w, tight, false, DATA, out.resolve("upload"), System.out).get(0);
        assertEquals((double) w.total(), r.metrics.get("total_requests"), "every uploaded request is sent");
    }

    @Test void wikipediaScenarioRuns() throws Exception {
        RunResult r = BudgetGuard.runChild("observe", tight, BudgetGuard.Scenario.wikipediaDocumented(), DATA, out.resolve("wiki"), null, System.out);
        assertTrue(r.metrics.get("total_requests") > 0);
        assertEquals(4 * 60 / 2, r.decisions.size() - 1, "one decision per 2-minute interval over 4 hours");
    }
}
