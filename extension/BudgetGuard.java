/*
 * Budget-Guarded, Explainable Auto-Scaling — an extension for AutoScaleSim.
 *
 * AutoScaleSim itself (everything under src/) is the original work of
 * M. S. Aslanpour, A. N. Toosi, J. Taheri and R. Gaire,
 * "AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds",
 * Simulation Modelling Practice and Theory, 2021, doi:10.1016/j.simpat.2020.102245,
 * https://github.com/aslanpour/AutoScaleSim
 *
 * THIS FILE is the student extension. It does not edit any original source file.
 * It plugs in through one documented extension point: a subclass of the original
 * PlannerRuleBased, which runs the original rule first and then (a) explains the
 * decision and (b) optionally applies a budget guard to it.
 *
 * Commands (run from the repository root, see the guide for the classpath):
 *   run      --mode original|observe|guard   one simulation in this JVM (used by the others)
 *   compare  --budget-inr N --target-rt S    original vs analysed vs guarded + HTML report
 *   sweep    --budgets a,b,c --target-rt S   evaluation over several budgets
 *   test     [--integration]                 self-tests (no external test library needed)
 *   serve    [--port 8080]                   web interface that runs real simulations
 */
package budgetguard;

import autoscalesim.ExperimentalSetup;
import autoscalesim.ExperimentalSetup.ExecutorType;
import autoscalesim.ExperimentalSetup.Method;
import autoscalesim.ExperimentalSetup.ScalingRule;
import autoscalesim.ExperimentalSetup.SurplusVMSelectionPolicy;
import autoscalesim.applicationprovider.ApplicationProvider;
import autoscalesim.applicationprovider.autoscaling.Analyzer;
import autoscalesim.applicationprovider.autoscaling.Executor;
import autoscalesim.applicationprovider.autoscaling.ExecutorSimple;
import autoscalesim.applicationprovider.autoscaling.Monitor;
import autoscalesim.applicationprovider.autoscaling.Planner;
import autoscalesim.applicationprovider.autoscaling.PlannerRuleBased;
import autoscalesim.applicationprovider.autoscaling.knowledgebase.AnalyzerHistory;
import autoscalesim.applicationprovider.autoscaling.knowledgebase.PlannerHistory;
import autoscalesim.applicationprovider.loadmanager.LoadAdmission;
import autoscalesim.applicationprovider.loadmanager.LoadBalancing;
import autoscalesim.applicationprovider.loadmanager.LoadBalancingRoundRobin;
import autoscalesim.cloudprovider.Vm;
import autoscalesim.log.AutoScaleSimTags;
import autoscalesim.log.AutoScaleSimTags.DATASET;
import autoscalesim.log.DateTime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.VmList;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BudgetGuard {

    // ------------------------------------------------------------------------------------------
    // 1. Configuration (validated)
    // ------------------------------------------------------------------------------------------

    /** User inputs of the extension. All money inside the simulator is USD; INR is for display/input. */
    public static final class GuardConfig {
        public final double budgetInr;     // budget for the whole simulated period (renting cost only)
        public final double targetRtSec;   // performance target: max acceptable average response time (s)
        public final double inrPerUsd;     // DOCUMENTED ASSUMPTION: exchange rate, user-configurable

        public GuardConfig(double budgetInr, double targetRtSec, double inrPerUsd) {
            if (!(budgetInr > 0) || Double.isInfinite(budgetInr))
                throw new IllegalArgumentException("Budget must be a positive number of INR (got " + budgetInr + ")");
            if (!(targetRtSec > 0) || Double.isInfinite(targetRtSec))
                throw new IllegalArgumentException("Response-time target must be a positive number of seconds (got " + targetRtSec + ")");
            if (!(inrPerUsd > 0) || Double.isInfinite(inrPerUsd))
                throw new IllegalArgumentException("INR per USD must be a positive number (got " + inrPerUsd + ")");
            this.budgetInr = budgetInr;
            this.targetRtSec = targetRtSec;
            this.inrPerUsd = inrPerUsd;
        }

        public double budgetUsd() { return budgetInr / inrPerUsd; }
    }

    public static final double DEFAULT_INR_PER_USD = 85.0;

    // ------------------------------------------------------------------------------------------
    // 2. The guard policy — pure functions, unit-tested without running a simulation
    // ------------------------------------------------------------------------------------------

    public enum Status {
        OK,               // budget respected, performance target met
        PERF_MISS,        // budget respected, performance target missed (no budget reason to block)
        BUDGET_HOLD,      // scale-up blocked by budget while performance target is still met
        CONFLICT,         // performance target missed AND budget prevents adding / keeping capacity
        BUDGET_RECOVERY,  // spending ahead of the paced budget: one VM removed to slow spending
        INFEASIBLE        // spending ahead of budget even at the minimum fleet: no feasible configuration
    }

    public static final class Outcome {
        public final int guardedDecision;
        public final Status status;
        public final boolean budgetOk;   // committed spend within the paced allowance (before this action)
        public final boolean perfOk;

        Outcome(int guardedDecision, Status status, boolean budgetOk, boolean perfOk) {
            this.guardedDecision = guardedDecision;
            this.status = status;
            this.budgetOk = budgetOk;
            this.perfOk = perfOk;
        }
    }

    public static final class Policy {
        static final double EPS = 1e-9;
        static final int UP = AutoScaleSimTags.PLANNER_SCALING_UP;
        static final int DOWN = AutoScaleSimTags.PLANNER_SCALING_DOWN;
        static final int NOTHING = AutoScaleSimTags.PLANNER_DO_NOTHING;

        /**
         * Charge of one on-demand VM, using the ORIGINAL AutoScaleSim billing rule
         * (ApplicationProvider / ExperimentalResult.setRemainedVmsBill): ceil(hours alive) x hourly price.
         * A destroyed VM already carries its final bill. A live VM is charged at least one hour,
         * matching the original's initial bill (Vm constructor: setBill(price)).
         */
        public static double vmChargeUsd(boolean destroyed, double finalBill, double requestTime, double now, double pricePerHour) {
            if (destroyed) return finalBill;
            double hours = Math.ceil((now - requestTime) / (double) AutoScaleSimTags.anHour);
            return pricePerHour * Math.max(1.0, hours);
        }

        /**
         * Linear budget pacing: the budget available by the end of the current billing hour, minus a
         * reserve of one billed hour for the minimum fleet (which the guard can never remove).
         */
        public static double allowanceUsd(double budgetUsd, double now, double horizonSec, double reserveUsd) {
            double frac = (now + AutoScaleSimTags.anHour) / horizonSec;
            return budgetUsd * Math.min(1.0, Math.max(0.0, frac)) - reserveUsd;
        }

        /**
         * Decision rule of the budget guard. The guard never invents a scale-up; it may
         * (1) block the original's scale-up, or (2) remove one VM when spending is ahead of the paced budget.
         */
        public static Outcome decide(int original, double committedUsd, double priceUsd, double allowanceUsd,
                                     int activeVms, int minVms, double responseTime, double targetRt) {
            boolean perfOk = responseTime <= targetRt;
            boolean overNow = committedUsd > allowanceUsd + EPS;
            if (overNow) {
                if (activeVms > minVms)
                    return new Outcome(DOWN, perfOk ? Status.BUDGET_RECOVERY : Status.CONFLICT, false, perfOk);
                return new Outcome(NOTHING, Status.INFEASIBLE, false, perfOk);
            }
            if (original == UP) {
                if (committedUsd + priceUsd <= allowanceUsd + EPS)
                    return new Outcome(UP, perfOk ? Status.OK : Status.PERF_MISS, true, perfOk);
                return new Outcome(NOTHING, perfOk ? Status.BUDGET_HOLD : Status.CONFLICT, true, perfOk);
            }
            return new Outcome(original, perfOk ? Status.OK : Status.PERF_MISS, true, perfOk);
        }

        public static String decisionName(int d) {
            if (d == UP) return "SCALE_UP";
            if (d == DOWN) return "SCALE_DOWN";
            return "DO_NOTHING";
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Explanations — generated only from recorded numbers and the documented rules above
    // ------------------------------------------------------------------------------------------

    public static final class DecisionRecord {
        public double simTimeSec;
        public String clock;
        public double cpuUtil, delayTime, responseTime;
        public int activeVms;
        public int originalDecision, guardedDecision;
        public boolean applied;
        public double committedUsd, allowanceUsd, priceUsd;
        public Status status;
        public boolean budgetOk, perfOk;
        public String explanation;
    }

    public static String explain(DecisionRecord r, GuardConfig cfg, double dtUp, double dtDown, int minVms, boolean enforce) {
        double rate = cfg.inrPerUsd;
        StringBuilder s = new StringBuilder();
        // (a) trigger and original action — the default rule is SLA-aware on analysed delay time
        if (r.originalDecision == Policy.UP)
            s.append(String.format(Locale.ROOT, "Analysed delay time %.3f s was above the scale-up threshold (%.2f s), so the original SLA-aware rule decided SCALE_UP. ", r.delayTime, dtUp));
        else if (r.originalDecision == Policy.DOWN)
            s.append(String.format(Locale.ROOT, "Analysed delay time %.3f s was below the scale-down threshold (%.2f s), so the original rule decided SCALE_DOWN. ", r.delayTime, dtDown));
        else
            s.append(String.format(Locale.ROOT, "Analysed delay time %.3f s was between %.2f s and %.2f s, so the original rule decided DO_NOTHING. ", r.delayTime, dtDown, dtUp));
        // (b) budget reasoning
        double c = r.committedUsd * rate, a = r.allowanceUsd * rate, p = r.priceUsd * rate;
        switch (r.status) {
            case BUDGET_RECOVERY:
            case CONFLICT:
                if (!r.budgetOk) {
                    s.append(String.format(Locale.ROOT, "Committed renting cost ₹%.2f is already above the ₹%.2f the paced budget allows by the end of this hour, so the guard removes one VM (%d → %d) to slow spending. ", c, a, r.activeVms, r.activeVms - 1));
                } else {
                    s.append(String.format(Locale.ROOT, "Adding one VM would raise committed renting cost to ₹%.2f (one more billed hour costs ₹%.2f), above the paced allowance of ₹%.2f, so the guard keeps the current %d VM(s). ", c + p, p, a, r.activeVms));
                }
                break;
            case INFEASIBLE:
                s.append(String.format(Locale.ROOT, "Committed renting cost ₹%.2f is above the paced allowance ₹%.2f, but the fleet is already at the minimum of %d VM(s); no configuration within the budget exists at this point. ", c, a, minVms));
                break;
            case BUDGET_HOLD:
                s.append(String.format(Locale.ROOT, "Adding one VM would raise committed renting cost to ₹%.2f, above the paced allowance of ₹%.2f, so the guard keeps the current %d VM(s). ", c + p, a, r.activeVms));
                break;
            default:
                if (r.originalDecision == Policy.UP)
                    s.append(String.format(Locale.ROOT, "Adding one VM commits at least ₹%.2f (one billed hour); projected ₹%.2f is within the paced allowance of ₹%.2f, so the action is affordable. ", p, c + p, a));
                else
                    s.append(String.format(Locale.ROOT, "Committed renting cost ₹%.2f is within the paced allowance of ₹%.2f. ", c, a));
        }
        // (c) performance
        s.append(String.format(Locale.ROOT, "Observed response time %.3f s %s the %.2f s target.", r.responseTime, r.perfOk ? "meets" : "misses", cfg.targetRtSec));
        if (r.status == Status.CONFLICT)
            s.append(" WARNING: budget and performance target conflict here; the target is missed and the budget does not allow more capacity.");
        if (r.status == Status.INFEASIBLE)
            s.append(" WARNING: no feasible configuration; the budget is too small even for the minimum fleet.");
        if (!enforce && r.guardedDecision != r.originalDecision)
            s.append(" (Analysis only: the guard would have chosen " + Policy.decisionName(r.guardedDecision) + ", but this run executed the original decision.)");
        return s.toString();
    }

    // ------------------------------------------------------------------------------------------
    // 4. The integration point: a subclass of the ORIGINAL rule-based planner
    // ------------------------------------------------------------------------------------------

    public static final class GuardedPlanner extends PlannerRuleBased {
        final GuardConfig cfg;
        final boolean enforce;
        final double horizonSec, lookaheadSec, dtUp, dtDown;
        final int minVms;
        final List<DecisionRecord> records = new ArrayList<>();

        public GuardedPlanner(ScalingRule rule, int configurationType, double cpuUp, double cpuDown,
                              double dtUp, double dtDown, GuardConfig cfg, boolean enforce,
                              double horizonSec, double lookaheadSec, int minVms) {
            super(rule, configurationType, cpuUp, cpuDown, dtUp, dtDown);
            this.cfg = cfg;
            this.enforce = enforce;
            this.horizonSec = horizonSec;
            this.lookaheadSec = lookaheadSec;
            this.dtUp = dtUp;
            this.dtDown = dtDown;
            this.minVms = minVms;
        }

        @Override
        public void doPlanning() {
            super.doPlanning();                               // the original rule, unchanged
            PlannerHistory rec = latestHistoryRec();          // what the original Executor will read
            AnalyzerHistory a = ApplicationProvider.getAnalyzer().latestHistoryRec();
            double now = CloudSim.clock();
            double price = AutoScaleSimTags.VM_PRICE_ONDEMAND[getConfigurationType()];

            // Look ahead to the next decision point: a live VM that crosses into a new billed hour
            // before then is already committed, because the guard cannot act in between.
            double committed = 0;
            for (Vm vm : VmList.getOnDemandVmsList())
                committed += Policy.vmChargeUsd(vm.getStatus() == Vm.Destroyed, vm.getBill(), vm.getRequestTime(), now + lookaheadSec, vm.getPrice());
            int active = VmList.getOnDemandVmsList(new int[]{Vm.Requested, Vm.Started}).size();
            double allowance = Policy.allowanceUsd(cfg.budgetUsd(), now, horizonSec, minVms * price);

            Outcome o = Policy.decide(rec.getDecision(), committed, price, allowance, active, minVms, a.getResponseTime(), cfg.targetRtSec);

            DecisionRecord r = new DecisionRecord();
            r.simTimeSec = now;
            r.clock = DateTime.timeStamp().trim();
            r.cpuUtil = a.getCpuUtilization();
            r.delayTime = a.getDelayTime();
            r.responseTime = a.getResponseTime();
            r.activeVms = active;
            r.originalDecision = rec.getDecision();
            r.guardedDecision = o.guardedDecision;
            r.applied = enforce;
            r.committedUsd = committed;
            r.allowanceUsd = allowance;
            r.priceUsd = price;
            r.status = o.status;
            r.budgetOk = o.budgetOk;
            r.perfOk = o.perfOk;
            r.explanation = explain(r, cfg, dtUp, dtDown, minVms, enforce);
            records.add(r);

            if (enforce && o.guardedDecision != rec.getDecision()) {
                rec.setDecision(o.guardedDecision);
                setPlannerDecision(o.guardedDecision);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5. Running one simulation
    // ------------------------------------------------------------------------------------------

    /**
     * Same experiment as autoscalesim.ExperimentalSetup.main (default NASA scenario).
     * Every value below is copied from ExperimentalSetup.main; the original private helper
     * methods are called by reflection so entity creation is identical. The integration test
     * checks that "observe" mode produces byte-identical output CSVs to the original main().
     */
    static void runSetup(GuardConfig cfg, boolean enforce) throws Exception {
        System.out.println("Starting AutoScaleSim Simulation... (BudgetGuard " + (enforce ? "guard" : "observe") + " mode)");
        call("cloudSimSetup", new Class<?>[]{});

        final int SIMULATION_LIMIT = 7 * 1440;
        DATASET datasetType = DATASET.NASA;
        final int CLOUDLET_LENGTH = 5000;
        final int PES_NUMBER = 2;
        int cloudletTimeout = 30;
        int minCloudletLength = 0;
        int maxCloudletLength = 245000;

        LoadAdmission loadAdmission = new LoadAdmission();
        LoadBalancing loadBalancing = new LoadBalancingRoundRobin();
        int cloudletSchedulerName = AutoScaleSimTags.TimeShared;
        int initialVMs = 2;
        int scalingInterval = 10;
        double slaContractOnDelayTime = 1.0;
        Monitor monitor = new Monitor();
        String[] analysisMethod = new String[10];
        Arrays.fill(analysisMethod, Method.SIMPLE.name());
        double[] sESAlpha = {0.2, 0, 0, 0, 0.1, 0, 0, 0, 0, 0};
        int timeWindow = scalingInterval;
        Analyzer analyzer = new Analyzer(analysisMethod, timeWindow, sESAlpha);

        final ScalingRule rule = ScalingRule.SLA_AWARE;
        final int configurationType = AutoScaleSimTags.VM_CONFIG_T2MEDIUM;
        double cpuScaleUpThreshold = 70, cpuScaleDownThreshold = 40;
        double delayTimeScaleUpThreshold = 1, delayTimeScaleDownThreshold = 0.2;
        final int maxOnDemandVm = 40, minOnDemandVm = 1;

        GuardedPlanner planner = new GuardedPlanner(rule, configurationType, cpuScaleUpThreshold, cpuScaleDownThreshold,
                delayTimeScaleUpThreshold, delayTimeScaleDownThreshold, cfg, enforce,
                SIMULATION_LIMIT * (double) AutoScaleSimTags.aMinute, scalingInterval * (double) AutoScaleSimTags.aMinute, minOnDemandVm);

        final ExecutorType executorType = ExecutorType.SIMPLE;
        final SurplusVMSelectionPolicy surplus = SurplusVMSelectionPolicy.THE_OLDEST;
        final int COOLDOWN = 0 * AutoScaleSimTags.aMinute;

        call("createCloudProvider", new Class<?>[]{int.class}, cloudletTimeout);
        String startUpDelayType = "Static";
        final double BASE_DELAY_IN_VM_START_UP = 5 * AutoScaleSimTags.aMinute;
        Executor executor = new ExecutorSimple(executorType, surplus, COOLDOWN, maxOnDemandVm, minOnDemandVm,
                startUpDelayType, BASE_DELAY_IN_VM_START_UP);
        final String[] reports = new String[]{"M_VM", "M_User", "M_SLA", "", "", ""};

        call("createEndUserEmulator",
                new Class<?>[]{String.class, int.class, DATASET.class, int.class, int.class, int.class, int.class},
                "EndUserEmulator", SIMULATION_LIMIT, datasetType, CLOUDLET_LENGTH, PES_NUMBER, minCloudletLength, maxCloudletLength);
        call("createApplicationProvider",
                new Class<?>[]{String.class, int.class, DATASET.class, LoadAdmission.class, LoadBalancing.class, int.class,
                        int.class, double.class, Monitor.class, Analyzer.class, Planner.class, Executor.class, String[].class},
                "ApplicationProvider", initialVMs, datasetType, loadAdmission, loadBalancing, cloudletSchedulerName,
                scalingInterval, slaContractOnDelayTime, monitor, analyzer, planner, executor, reports);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        writeDecisions(Paths.get("guard_decisions.csv"), planner.records, cfg);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = ExperimentalSetup.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    static final String[] DECISION_HEADER = {"sim_time_s", "clock", "cpu_util_pct", "delay_time_s", "response_time_s",
            "active_vms", "original_decision", "guard_decision", "guard_applied", "committed_cost_inr",
            "paced_allowance_inr", "budget_ok", "perf_target_ok", "status", "explanation"};

    static void writeDecisions(Path file, List<DecisionRecord> recs, GuardConfig cfg) throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(DECISION_HEADER);
        for (DecisionRecord r : recs) {
            rows.add(new String[]{
                    fmt(r.simTimeSec, 0), r.clock, fmt(r.cpuUtil, 3), fmt(r.delayTime, 4), fmt(r.responseTime, 4),
                    String.valueOf(r.activeVms), Policy.decisionName(r.originalDecision), Policy.decisionName(r.guardedDecision),
                    String.valueOf(r.applied), fmt(r.committedUsd * cfg.inrPerUsd, 2), fmt(r.allowanceUsd * cfg.inrPerUsd, 2),
                    String.valueOf(r.budgetOk), String.valueOf(r.perfOk), r.status.name(), r.explanation});
        }
        Csv.write(file, rows);
    }

    // ------------------------------------------------------------------------------------------
    // 6. Orchestration: each simulation runs in its own JVM and working directory
    //    (AutoScaleSim/CloudSim keep static state and use the relative path "src/others/")
    // ------------------------------------------------------------------------------------------

    public static final class RunResult {
        public String mode;
        public Path dir;
        public Map<String, Double> metrics = new LinkedHashMap<>();
        public List<String[]> decisions = new ArrayList<>();
        public double[] hourlyVms = new double[0], hourlyRt = new double[0];
        public int exitCode;
        public long seconds;
    }

    static RunResult runChild(String mode, GuardConfig cfg, Path dataDir, Path runDir, PrintStream progress) throws Exception {
        Path others = runDir.resolve("src").resolve("others");
        Files.createDirectories(others);
        Path tset = others.resolve("Dataset_NASA_July.tset");
        if (!Files.exists(tset)) {
            Path src = dataDir.resolve("Dataset_NASA_July.tset");
            if (!Files.exists(src)) throw new FileNotFoundException("Dataset not found: " + src.toAbsolutePath());
            try { Files.createLink(tset, src.toAbsolutePath()); } catch (Exception e) { Files.copy(src, tset); }
        }
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        StringBuilder cp = new StringBuilder();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator))
            if (!e.isEmpty()) cp.append(cp.length() > 0 ? File.pathSeparator : "").append(Paths.get(e).toAbsolutePath());
        List<String> cmd = new ArrayList<>(Arrays.asList(java, "-Xmx2g", "-Duser.language=en", "-Duser.country=US",
                "-cp", cp.toString(), BudgetGuard.class.getName(), "run", "--mode", mode,
                "--budget-inr", String.valueOf(cfg.budgetInr), "--target-rt", String.valueOf(cfg.targetRtSec),
                "--inr-per-usd", String.valueOf(cfg.inrPerUsd)));
        if (progress != null) progress.println("  running " + mode + " simulation in " + runDir + " ...");
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).directory(runDir.toFile()).redirectErrorStream(true)
                .redirectOutput(runDir.resolve("console.log").toFile()).start();
        int code = p.waitFor();
        RunResult rr = collect(mode, runDir);
        rr.exitCode = code;
        rr.seconds = (System.currentTimeMillis() - t0) / 1000;
        if (code != 0 || rr.metrics.get("renting_cost_usd") == null)
            throw new IllegalStateException(mode + " simulation failed (exit " + code + "); see " + runDir.resolve("console.log"));
        if (progress != null) progress.println("  " + mode + " finished in " + rr.seconds + " s");
        return rr;
    }

    static RunResult collect(String mode, Path runDir) throws IOException {
        RunResult rr = new RunResult();
        rr.mode = mode;
        rr.dir = runDir;
        rr.metrics = ConsoleParser.parse(new String(Files.readAllBytes(runDir.resolve("console.log")), StandardCharsets.UTF_8));
        Path dec = runDir.resolve("guard_decisions.csv");
        if (Files.exists(dec)) rr.decisions = Csv.read(dec);
        Path others = runDir.resolve("src").resolve("others");
        if (Files.exists(others.resolve("Monitor_VmHistory.csv")))
            rr.hourlyVms = hourlyMean(Csv.read(others.resolve("Monitor_VmHistory.csv")), "VMs No");
        if (Files.exists(others.resolve("Monitor_SLAHistory.csv")))
            rr.hourlyRt = hourlyMean(Csv.read(others.resolve("Monitor_SLAHistory.csv")), "Response Time");
        return rr;
    }

    /** Mean of each block of 60 per-minute rows of an original Monitor_*.csv column. */
    static double[] hourlyMean(List<String[]> rows, String column) {
        int col = -1;
        for (int i = 0; i < rows.get(0).length; i++) if (rows.get(0)[i].trim().equalsIgnoreCase(column)) col = i;
        if (col < 0) throw new IllegalArgumentException("Column not found: " + column);
        int n = rows.size() - 1, hours = (n + 59) / 60;
        double[] out = new double[hours];
        for (int h = 0; h < hours; h++) {
            double s = 0; int k = 0;
            for (int i = 1 + h * 60; i <= Math.min(n, (h + 1) * 60); i++) { s += Double.parseDouble(rows.get(i)[col].trim()); k++; }
            out[h] = k == 0 ? 0 : s / k;
        }
        return out;
    }

    /** Reads the summary that the ORIGINAL ExperimentalResult prints at the end of every run. */
    public static final class ConsoleParser {
        static final String[][] PATTERNS = {
                {"total_requests", "Total User requests : ([\\d.]+)"},
                {"cpu_util_avg_pct", "CPU Utilization \\(%\\):([\\d.]+) Avg"},
                {"response_time_avg_s", "Response Time \\(Sec\\.\\): ([\\d.]+) Avg"},
                {"delay_time_avg_s", "Delay Time \\(Sec\\.\\): ([\\d.]+) Avg"},
                {"cloudlets_cancelled", "Cloudlet Canceled: (\\d+)"},
                {"cloudlets_failed", "Cloudlet Failured: (\\d+)"},
                {"sla_violation_pct", "SLA Violation: ([\\d.]+)%"},
                {"max_vms", "Max used Vms: (\\d+)"},
                {"scale_up_decisions", "Scale Up Decisions: (\\d+)"},
                {"scale_down_decisions", "Scale Down Decisions: (\\d+)"},
                {"contradictory_decisions", "Contradictory Scaling Decisions: (\\d+)"},
                {"vms_provisioned", "Provisioned On-Demand Vm:(\\d+)"},
                {"vms_deprovisioned", "De-Provisioned On-Demand Vm:(\\d+)"},
                {"renting_cost_usd", "Renting Cost: \\$ ([\\d.]+)"},
                {"sla_penalty_usd", "SLA Penalty: \\$ ([\\d.]+)"},
                {"total_cost_usd", "Total Cost : \\$ \\.+\\s+([\\d.]+)"},
        };

        public static Map<String, Double> parse(String console) {
            String text = console.replaceAll("\u001B\\[[0-9;]*m", "");
            int start = text.lastIndexOf("EXPERIMENTAL RESULTS");
            if (start >= 0) text = text.substring(Math.max(0, text.lastIndexOf("Total User requests", start)));
            Map<String, Double> m = new LinkedHashMap<>();
            for (String[] p : PATTERNS) {
                Matcher x = Pattern.compile(p[1]).matcher(text);
                if (x.find()) m.put(p[0], Double.parseDouble(x.group(1)));
            }
            return m;
        }
    }

    static final class Comparison {
        GuardConfig cfg;
        RunResult original, observe, guard;
        Path dir;
    }

    static Comparison compare(GuardConfig cfg, Path dataDir, Path outDir, PrintStream progress) throws Exception {
        Files.createDirectories(outDir);
        Comparison c = new Comparison();
        c.cfg = cfg;
        c.dir = outDir;
        c.original = runChild("original", cfg, dataDir, outDir.resolve("original"), progress);
        c.observe = runChild("observe", cfg, dataDir, outDir.resolve("observe"), progress);
        c.guard = runChild("guard", cfg, dataDir, outDir.resolve("guard"), progress);
        Report.writeComparison(c);
        writeMeta(outDir, cfg, c);
        if (progress != null) progress.println("Report: " + outDir.resolve("report.html").toAbsolutePath());
        return c;
    }

    static void writeMeta(Path dir, GuardConfig cfg, Comparison c) throws IOException {
        Properties p = new Properties();
        p.setProperty("budget_inr", String.valueOf(cfg.budgetInr));
        p.setProperty("target_rt_s", String.valueOf(cfg.targetRtSec));
        p.setProperty("inr_per_usd", String.valueOf(cfg.inrPerUsd));
        p.setProperty("original_renting_inr", fmt(c.original.metrics.get("renting_cost_usd") * cfg.inrPerUsd, 2));
        p.setProperty("guard_renting_inr", fmt(c.guard.metrics.get("renting_cost_usd") * cfg.inrPerUsd, 2));
        try (Writer w = Files.newBufferedWriter(dir.resolve("meta.properties"))) { p.store(w, "BudgetGuard comparison"); }
    }

    // ------------------------------------------------------------------------------------------
    // 7. HTML report (self-contained, inline SVG charts, no external libraries)
    // ------------------------------------------------------------------------------------------

    static final class Report {
        static final String CSS = ":root{color-scheme:light;--bg:#fcfcfb;--card:#ffffff;--ink:#0b0b0b;--ink2:#52514e;--muted:#8a8984;--line:#e4e3df;"
                + "--s1:#2a78d6;--s2:#eb6834;--ref:#8a8984;--good:#1a7f37;--bad:#c62828;--warn:#9a6700}"
                + "@media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;--bg:#1a1a19;--card:#232322;--ink:#f0efec;--ink2:#c3c2b7;--muted:#8f8e86;--line:#3a3a37;--s1:#3987e5;--s2:#d95926;--ref:#8f8e86;--good:#4cc36a;--bad:#ef6b6b;--warn:#e0a83a}}"
                + "body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
                + "main{max-width:1100px;margin:0 auto;padding:24px 16px 64px}h1{font-size:24px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 8px}"
                + ".sub{color:var(--ink2);margin:0 0 16px}.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:12px 0}"
                + "table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}"
                + "th{color:var(--ink2);font-weight:600}td.n{text-align:right;font-variant-numeric:tabular-nums}.scroll{overflow-x:auto}"
                + ".tag{display:inline-block;border-radius:4px;padding:0 6px;font-size:12px;font-weight:600;border:1px solid currentColor}"
                + ".OK{color:var(--good)}.PERF_MISS{color:var(--warn)}.BUDGET_HOLD{color:var(--ink2)}.BUDGET_RECOVERY{color:var(--ink2)}.CONFLICT,.INFEASIBLE{color:var(--bad)}"
                + ".legend{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;color:var(--ink2)}.sw{display:inline-block;width:14px;height:3px;border-radius:2px;vertical-align:middle;margin-right:6px}"
                + "svg text{fill:var(--muted);font-size:11px}.note{font-size:13px;color:var(--ink2)}.warnbox{border-left:4px solid var(--bad);padding:8px 12px;margin:8px 0}"
                + "select,input,button{font:inherit;padding:6px 8px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--ink)}button{cursor:pointer}"
                + ".tip{position:fixed;pointer-events:none;background:var(--card);border:1px solid var(--line);border-radius:6px;padding:6px 8px;font-size:12px;display:none;box-shadow:0 2px 8px rgba(0,0,0,.15)}";

        static void writeComparison(Comparison c) throws IOException {
            GuardConfig cfg = c.cfg;
            double rate = cfg.inrPerUsd;
            StringBuilder h = new StringBuilder();
            h.append("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                    .append("<title>BudgetGuard Comparison</title><style>").append(CSS).append("</style></head><body><main>");
            h.append("<h1>AutoScaleSim: original vs budget-guarded</h1>");
            h.append("<p class=sub>Base simulator: Aslanpour, Toosi, Taheri &amp; Gaire, <i>AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds</i>, SIMPAT 2021, ")
                    .append("<a href='https://doi.org/10.1016/j.simpat.2020.102245'>doi:10.1016/j.simpat.2020.102245</a>. ")
                    .append("Extension: Budget-Guarded, Explainable Auto-Scaling (this project). Workload: NASA trace, 7 days, default configuration of <code>ExperimentalSetup</code>.</p>");

            h.append("<div class=card><b>Configuration</b><br>")
                    .append(String.format(Locale.ROOT, "Budget: ₹%.2f for the 7-day simulated period (renting cost only) &middot; Response-time target: %.2f s &middot; ", cfg.budgetInr, cfg.targetRtSec))
                    .append(String.format(Locale.ROOT, "Exchange rate assumption: ₹%.2f per US$ (the simulator's prices are in US$; t2.medium on-demand = US$%.2f/hour from <code>AutoScaleSimTags.VM_PRICE_ONDEMAND</code>)", rate, AutoScaleSimTags.VM_PRICE_ONDEMAND[AutoScaleSimTags.VM_CONFIG_T2MEDIUM]))
                    .append("</div>");

            // ---- summary table
            String[][] rows = {
                    {"Renting cost (₹)", "renting_cost_usd", "inr"}, {"SLA penalty (₹)", "sla_penalty_usd", "inr"},
                    {"Total cost (₹)", "total_cost_usd", "inr"}, {"Avg response time (s)", "response_time_avg_s", ""},
                    {"Avg delay time (s)", "delay_time_avg_s", ""}, {"SLA violation (%)", "sla_violation_pct", ""},
                    {"Avg CPU utilisation (%)", "cpu_util_avg_pct", ""}, {"Max VMs used", "max_vms", ""},
                    {"VMs provisioned", "vms_provisioned", ""}, {"VMs de-provisioned", "vms_deprovisioned", ""},
                    {"Contradictory decisions", "contradictory_decisions", ""}, {"Failed requests", "cloudlets_failed", ""},
                    {"Cancelled requests", "cloudlets_cancelled", ""}, {"Total requests", "total_requests", ""}};
            h.append("<h2>Summary</h2><div class='card scroll'><table><tr><th>Metric (source: AutoScaleSim's own end-of-run report)</th>")
                    .append("<th>1. Original AutoScaleSim</th><th>2. Original, analysed by extension</th><th>3. Budget-guarded policy</th></tr>");
            for (String[] r : rows) {
                h.append("<tr><td>").append(r[0]).append("</td>");
                for (RunResult rr : new RunResult[]{c.original, c.observe, c.guard}) {
                    Double v = rr.metrics.get(r[1]);
                    String s = v == null ? "n/a" : r[2].equals("inr") ? fmt(v * rate, 2) : (v == Math.rint(v) ? String.valueOf(v.longValue()) : fmt(v, 3));
                    h.append("<td class=n>").append(s).append("</td>");
                }
                h.append("</tr>");
            }
            double origR = c.original.metrics.get("renting_cost_usd") * rate, guardR = c.guard.metrics.get("renting_cost_usd") * rate;
            double guardRt = c.guard.metrics.getOrDefault("response_time_avg_s", Double.NaN);
            double origRt = c.original.metrics.getOrDefault("response_time_avg_s", Double.NaN);
            h.append("<tr><td><b>Renting cost within budget?</b></td>").append(yn(origR <= cfg.budgetInr)).append(yn(origR <= cfg.budgetInr)).append(yn(guardR <= cfg.budgetInr)).append("</tr>");
            h.append("<tr><td><b>Avg response time meets target?</b></td>").append(yn(origRt <= cfg.targetRtSec)).append(yn(origRt <= cfg.targetRtSec)).append(yn(guardRt <= cfg.targetRtSec)).append("</tr>");
            h.append("</table><p class=note>Columns 1 and 2 are the same simulation (the extension only observes in column 2); they must be identical. ")
                    .append("Column 3 is a different simulation in which the guard's decisions are executed. Budget compliance and target checks are computed by the extension from these numbers.</p></div>");

            // ---- warnings
            Map<String, Integer> cntObs = countStatus(c.observe.decisions), cntGuard = countStatus(c.guard.decisions);
            h.append("<h2>Budget and performance warnings</h2><div class=card>");
            if (guardR > cfg.budgetInr)
                h.append("<div class=warnbox>The budget-guarded run still exceeded the budget (₹").append(fmt(guardR, 2))
                        .append(" &gt; ₹").append(fmt(cfg.budgetInr, 2)).append("). See INFEASIBLE rows: the minimum fleet alone costs more than the paced budget.</div>");
            if (guardRt > cfg.targetRtSec)
                h.append("<div class=warnbox>Under the budget, the average response time (").append(fmt(guardRt, 3)).append(" s) misses the ")
                        .append(fmt(cfg.targetRtSec, 2)).append(" s target. Budget and performance target cannot both be satisfied with this configuration.</div>");
            h.append("<table><tr><th>Decision status</th><th>Original, analysed (count)</th><th>Budget-guarded (count)</th><th>Meaning</th></tr>");
            String[][] meaning = {{"OK", "within paced budget, target met"}, {"PERF_MISS", "within budget, target missed"},
                    {"BUDGET_HOLD", "scale-up blocked by budget, target still met"}, {"CONFLICT", "target missed and budget blocks capacity"},
                    {"BUDGET_RECOVERY", "spending ahead of budget, one VM removed"}, {"INFEASIBLE", "over budget even at minimum fleet"}};
            for (String[] m : meaning)
                h.append("<tr><td><span class='tag ").append(m[0]).append("'>").append(m[0]).append("</span></td><td class=n>")
                        .append(cntObs.getOrDefault(m[0], 0)).append("</td><td class=n>").append(cntGuard.getOrDefault(m[0], 0))
                        .append("</td><td>").append(m[1]).append("</td></tr>");
            h.append("</table><p class=note>In column 2 the statuses describe what the guard would have flagged on the original run; the original decisions were still executed.</p></div>");

            // ---- charts
            h.append("<h2>Cumulative renting cost vs paced budget</h2><div class=card>");
            double[] tObs = col(c.observe.decisions, 0), costObs = col(c.observe.decisions, 9), allow = col(c.observe.decisions, 10);
            double[] tG = col(c.guard.decisions, 0), costG = col(c.guard.decisions, 9);
            h.append(legend(new String[]{"Original", "Budget-guarded", "Paced budget"}, new String[]{"var(--s1)", "var(--s2)", "var(--ref)"}, new boolean[]{false, false, true}));
            h.append(Svg.lines("cost", "Hours", "₹ committed", new double[][]{div(tObs, 3600), div(tG, 3600), div(tObs, 3600)},
                    new double[][]{costObs, costG, allow}, new String[]{"Original", "Budget-guarded", "Paced budget"},
                    new String[]{"var(--s1)", "var(--s2)", "var(--ref)"}, new boolean[]{false, false, true}));
            h.append("<p class=note>Committed cost = billed hours each VM will have reached by the next scaling decision (each VM charged ceil(hours alive) &times; hourly price, the original AutoScaleSim rule), sampled at every scaling decision (every 10 simulated minutes).</p></div>");

            h.append("<h2>VMs in service (hourly mean)</h2><div class=card>");
            h.append(legend(new String[]{"Original", "Budget-guarded"}, new String[]{"var(--s1)", "var(--s2)"}, new boolean[]{false, false}));
            h.append(Svg.lines("vms", "Hours", "VMs", new double[][]{idx(c.original.hourlyVms), idx(c.guard.hourlyVms)},
                    new double[][]{c.original.hourlyVms, c.guard.hourlyVms}, new String[]{"Original", "Budget-guarded"},
                    new String[]{"var(--s1)", "var(--s2)"}, new boolean[]{false, false}));
            h.append("<p class=note>From the original Monitor_VmHistory.csv written by each run (per-minute rows averaged per hour).</p></div>");

            h.append("<h2>Response time (hourly mean)</h2><div class=card>");
            h.append(legend(new String[]{"Original", "Budget-guarded", "Target"}, new String[]{"var(--s1)", "var(--s2)", "var(--ref)"}, new boolean[]{false, false, true}));
            double[] hrs = idx(c.original.hourlyRt), target = new double[hrs.length];
            Arrays.fill(target, cfg.targetRtSec);
            h.append(Svg.lines("rt", "Hours", "Seconds", new double[][]{hrs, idx(c.guard.hourlyRt), hrs},
                    new double[][]{c.original.hourlyRt, c.guard.hourlyRt, target}, new String[]{"Original", "Budget-guarded", "Target"},
                    new String[]{"var(--s1)", "var(--s2)", "var(--ref)"}, new boolean[]{false, false, true}));
            h.append("<p class=note>From the original Monitor_SLAHistory.csv (per-minute average response time, averaged per hour).</p></div>");

            // ---- explanations
            h.append("<h2>Scaling-decision explanations</h2><div class=card>")
                    .append("<label>Run <select id=run><option value=g>Budget-guarded (decisions executed)</option><option value=o>Original, analysed (decisions not changed)</option></select></label> ")
                    .append("<label>Status <select id=st><option value=''>All except OK</option><option value='*'>All</option>");
            for (String[] m : meaning) h.append("<option>").append(m[0]).append("</option>");
            h.append("</select></label><div class=scroll>");
            h.append(decisionTable("g", c.guard.decisions)).append(decisionTable("o", c.observe.decisions));
            h.append("</div></div>");

            h.append("<h2>Downloads</h2><div class=card><ul>")
                    .append("<li><a href='guard/guard_decisions.csv'>Budget-guarded decision log (CSV)</a></li>")
                    .append("<li><a href='observe/guard_decisions.csv'>Original run analysed by the extension (CSV)</a></li>")
                    .append("<li><a href='original/console.log'>Original console output</a> &middot; <a href='guard/console.log'>Guarded console output</a></li>")
                    .append("<li><a href='original/src/others/Monitor_VmHistory.csv'>Original Monitor_VmHistory.csv</a> &middot; <a href='original/src/others/Monitor_SLAHistory.csv'>Monitor_SLAHistory.csv</a></li>")
                    .append("</ul></div>");
            h.append("<div class=tip id=tip></div>");
            h.append("<script>").append(JS).append("</script></main></body></html>");
            Files.write(c.dir.resolve("report.html"), h.toString().getBytes(StandardCharsets.UTF_8));
        }

        static final String JS = "function f(){var r=document.getElementById('run').value,s=document.getElementById('st').value;"
                + "document.querySelectorAll('table.dec').forEach(function(t){t.style.display=t.dataset.run===r?'':'none'});"
                + "document.querySelectorAll('table.dec tr[data-s]').forEach(function(tr){var v=tr.dataset.s;tr.style.display=(s==='*'||(s===''&&v!=='OK')||s===v)?'':'none'})}"
                + "document.getElementById('run').onchange=f;document.getElementById('st').onchange=f;f();"
                + "var tip=document.getElementById('tip');document.querySelectorAll('svg.chart').forEach(function(svg){var d=JSON.parse(svg.dataset.pts),hl=svg.querySelector('.hover');"
                + "svg.addEventListener('mousemove',function(e){var b=svg.getBoundingClientRect(),x=(e.clientX-b.left)/b.width*svg.viewBox.baseVal.width;"
                + "var best=null;d.forEach(function(s){var m=null;s.p.forEach(function(p){if(m===null||Math.abs(p[0]-x)<Math.abs(m[0]-x))m=p});if(m)best=best||m,s.m=m});"
                + "if(!best)return;hl.setAttribute('x1',best[0]);hl.setAttribute('x2',best[0]);hl.style.display='inline';"
                + "tip.innerHTML=d.map(function(s){return s.m?('<div><b>'+s.n+'</b>: '+s.m[2]+'</div>'):''}).join('')+'<div>'+svg.dataset.x+' '+best[3]+'</div>';"
                + "tip.style.display='block';tip.style.left=(e.clientX+12)+'px';tip.style.top=(e.clientY+12)+'px'});"
                + "svg.addEventListener('mouseleave',function(){tip.style.display='none';hl.style.display='none'})});";

        static String decisionTable(String id, List<String[]> rows) {
            StringBuilder h = new StringBuilder("<table class=dec data-run=" + id + "><tr><th>Time</th><th>Delay / RT (s)</th><th>VMs</th>"
                    + "<th>Original</th><th>Guard</th><th>Committed / allowance (₹)</th><th>Status</th><th>Explanation</th></tr>");
            for (int i = 1; i < rows.size(); i++) {
                String[] r = rows.get(i);
                int next = i + 1 < rows.size() ? Integer.parseInt(rows.get(i + 1)[5]) : -1;
                h.append("<tr data-s=").append(r[13]).append("><td>").append(esc(r[1])).append("</td><td class=n>")
                        .append(fmt(Double.parseDouble(r[3]), 3)).append(" / ").append(fmt(Double.parseDouble(r[4]), 3))
                        .append("</td><td class=n>").append(r[5]).append(next >= 0 ? " → " + next : "").append("</td><td>").append(r[6]).append("</td><td>").append(r[7])
                        .append("</td><td class=n>").append(r[9]).append(" / ").append(r[10]).append("</td><td><span class='tag ").append(r[13]).append("'>")
                        .append(r[13]).append("</span></td><td>").append(esc(r[14])).append("</td></tr>");
            }
            return h.append("</table>").toString();
        }

        static String legend(String[] names, String[] colors, boolean[] dashed) {
            StringBuilder s = new StringBuilder("<div class=legend>");
            for (int i = 0; i < names.length; i++)
                s.append("<span><span class=sw style='background:").append(dashed[i] ? "repeating-linear-gradient(90deg," + colors[i] + " 0 4px,transparent 4px 7px)" : colors[i])
                        .append("'></span>").append(names[i]).append("</span>");
            return s.append("</div>").toString();
        }

        static String yn(boolean b) { return "<td class=n><span class='tag " + (b ? "OK'>yes" : "CONFLICT'>no") + "</span></td>"; }
    }

    static final class Svg {
        static String lines(String id, String xLabel, String yLabel, double[][] xs, double[][] ys, String[] names, String[] colors, boolean[] dashed) {
            int W = 1000, H = 300, L = 56, R = 16, T = 12, B = 36;
            double xmax = 0, ymax = 0;
            for (double[] x : xs) for (double v : x) xmax = Math.max(xmax, v);
            for (double[] y : ys) for (double v : y) ymax = Math.max(ymax, v);
            if (xmax <= 0) xmax = 1;
            double step = niceStep(ymax / 4);
            ymax = Math.max(step, Math.ceil(ymax / step) * step);
            StringBuilder s = new StringBuilder();
            StringBuilder pts = new StringBuilder("[");
            s.append("<svg class='chart' role='img' aria-label='").append(yLabel).append(" over ").append(xLabel).append("' viewBox='0 0 ").append(W).append(' ').append(H)
                    .append("' style='width:100%;height:auto' data-x='").append(xLabel).append("' data-pts='__PTS__'>");
            for (double v = 0; v <= ymax + 1e-9; v += step) {
                double y = T + (H - T - B) * (1 - v / ymax);
                s.append(String.format(Locale.ROOT, "<line x1='%d' x2='%d' y1='%.1f' y2='%.1f' style='stroke:var(--line)' stroke-width='1'/>", L, W - R, y, y));
                s.append(String.format(Locale.ROOT, "<text x='%d' y='%.1f' text-anchor='end' dominant-baseline='middle'>%s</text>", L - 6, y, trimNum(v)));
            }
            double xstep = niceStep(xmax / 7);
            for (double v = 0; v <= xmax + 1e-9; v += xstep) {
                double x = L + (W - L - R) * v / xmax;
                s.append(String.format(Locale.ROOT, "<text x='%.1f' y='%d' text-anchor='middle'>%s</text>", x, H - B + 16, trimNum(v)));
            }
            s.append(String.format(Locale.ROOT, "<text x='%d' y='%d' text-anchor='middle'>%s</text>", (L + W - R) / 2, H - 4, xLabel));
            s.append(String.format(Locale.ROOT, "<text x='12' y='%d' transform='rotate(-90 12 %d)' text-anchor='middle'>%s</text>", (T + H - B) / 2, (T + H - B) / 2, esc(yLabel)));
            for (int k = 0; k < ys.length; k++) {
                StringBuilder path = new StringBuilder();
                pts.append(k > 0 ? "," : "").append("{\"n\":\"").append(names[k]).append("\",\"p\":[");
                int n = Math.min(xs[k].length, ys[k].length);
                for (int i = 0; i < n; i++) {
                    double x = L + (W - L - R) * xs[k][i] / xmax, y = T + (H - T - B) * (1 - ys[k][i] / ymax);
                    path.append(i == 0 ? "M" : "L").append(String.format(Locale.ROOT, "%.1f %.1f", x, y));
                    pts.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.1f,%.1f,\"%s\",\"%s\"]", x, y, fmt(ys[k][i], 2), fmt(xs[k][i], 1)));
                }
                pts.append("]}");
                s.append("<path d='").append(path).append("' fill='none' style='stroke:").append(colors[k]).append("' stroke-width='2' stroke-linejoin='round'")
                        .append(dashed[k] ? " stroke-dasharray='6 4'" : "").append("/>");
            }
            pts.append("]");
            s.append(String.format(Locale.ROOT, "<line class='hover' y1='%d' y2='%d' stroke-width='1' style='stroke:var(--muted);display:none'/>", T, H - B));
            s.append(String.format(Locale.ROOT, "<line x1='%d' x2='%d' y1='%d' y2='%d' style='stroke:var(--muted)' stroke-width='1'/>", L, W - R, H - B, H - B));
            s.append("</svg>");
            return s.toString().replace("__PTS__", pts.toString().replace("'", "&#39;"));
        }

        static double niceStep(double raw) {
            if (raw <= 0) return 1;
            double p = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / p;
            return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
        }

        static String trimNum(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : fmt(v, 2); }
    }

    // ------------------------------------------------------------------------------------------
    // 8. Evaluation sweep over several budgets (same workload and parameters for every run)
    // ------------------------------------------------------------------------------------------

    static void sweep(double[] budgets, double targetRt, double rate, Path dataDir, Path outDir, PrintStream out) throws Exception {
        Files.createDirectories(outDir);
        GuardConfig first = new GuardConfig(budgets[0], targetRt, rate);
        RunResult orig = runChild("original", first, dataDir, outDir.resolve("original"), out);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"run", "budget_inr", "renting_cost_inr", "within_budget", "total_cost_inr", "avg_response_time_s",
                "meets_rt_target", "sla_violation_pct", "max_vms", "vms_provisioned", "contradictory_decisions",
                "decisions_OK", "decisions_PERF_MISS", "decisions_BUDGET_HOLD", "decisions_CONFLICT", "decisions_BUDGET_RECOVERY", "decisions_INFEASIBLE"});
        rows.add(sweepRow("original", Double.NaN, orig, targetRt, rate, new HashMap<>()));
        for (double b : budgets) {
            GuardConfig cfg = new GuardConfig(b, targetRt, rate);
            RunResult g = runChild("guard", cfg, dataDir, outDir.resolve("guard_" + (long) b), out);
            rows.add(sweepRow("guard", b, g, targetRt, rate, countStatus(g.decisions)));
        }
        Csv.write(outDir.resolve("sweep.csv"), rows);
        StringBuilder h = new StringBuilder("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>BudgetGuard Sweep</title><style>")
                .append(Report.CSS).append("</style></head><body><main><h1>Budget sweep</h1><p class=sub>Same NASA 7-day workload and default AutoScaleSim parameters for every run; only the budget changes. ")
                .append(String.format(Locale.ROOT, "Response-time target %.2f s; ₹%.2f per US$ (assumption).", targetRt, rate))
                .append("</p><div class='card scroll'><table><tr>");
        for (String c : rows.get(0)) h.append("<th>").append(c).append("</th>");
        h.append("</tr>");
        for (int i = 1; i < rows.size(); i++) {
            h.append("<tr>");
            for (String c : rows.get(i)) h.append("<td class=n>").append(esc(c)).append("</td>");
            h.append("</tr>");
        }
        h.append("</table></div></main></body></html>");
        Files.write(outDir.resolve("sweep.html"), h.toString().getBytes(StandardCharsets.UTF_8));
        out.println("Sweep results: " + outDir.resolve("sweep.csv").toAbsolutePath() + " and sweep.html");
    }

    static String[] sweepRow(String run, double budget, RunResult r, double targetRt, double rate, Map<String, Integer> cnt) {
        double rc = r.metrics.get("renting_cost_usd") * rate, rt = r.metrics.get("response_time_avg_s");
        return new String[]{run, Double.isNaN(budget) ? "-" : fmt(budget, 0), fmt(rc, 2), Double.isNaN(budget) ? "-" : String.valueOf(rc <= budget),
                fmt(r.metrics.get("total_cost_usd") * rate, 2), fmt(rt, 3), String.valueOf(rt <= targetRt),
                fmt(r.metrics.get("sla_violation_pct"), 3), fmt(r.metrics.get("max_vms"), 0), fmt(r.metrics.get("vms_provisioned"), 0),
                fmt(r.metrics.get("contradictory_decisions"), 0),
                cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("OK", 0)), cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("PERF_MISS", 0)),
                cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("BUDGET_HOLD", 0)), cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("CONFLICT", 0)),
                cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("BUDGET_RECOVERY", 0)), cnt.isEmpty() ? "-" : String.valueOf(cnt.getOrDefault("INFEASIBLE", 0))};
    }

    // ------------------------------------------------------------------------------------------
    // 9. Web interface (JDK built-in HTTP server; each request runs real simulations)
    // ------------------------------------------------------------------------------------------

    static final AtomicReference<String> JOB = new AtomicReference<>(null);
    static final StringBuffer JOB_LOG = new StringBuffer();

    static void serve(int port, Path dataDir, Path resultsDir) throws IOException {
        Files.createDirectories(resultsDir);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", ex -> send(ex, 200, "text/plain", "ok"));
        server.createContext("/run", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
            Map<String, String> f = form(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
            GuardConfig cfg;
            try {
                cfg = new GuardConfig(num(f.get("budget")), num(f.get("target")), num(f.get("rate")));
            } catch (IllegalArgumentException e) {
                send(ex, 400, "text/html; charset=utf-8", page("Invalid input", "<div class='card warnbox'>" + esc(e.getMessage()) + "</div><a href='/'>Back</a>"));
                return;
            }
            String id = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "_b" + (long) cfg.budgetInr;
            if (!JOB.compareAndSet(null, id)) {
                send(ex, 409, "text/html; charset=utf-8", page("Busy", "<div class=card>A comparison is already running. <a href='/job'>See progress</a></div>"));
                return;
            }
            JOB_LOG.setLength(0);
            final GuardConfig c = cfg;
            new Thread(() -> {
                PrintStream ps = new PrintStream(new OutputStream() {
                    public void write(int b) { JOB_LOG.append((char) b); }
                }, true);
                try { compare(c, dataDir, resultsDir.resolve(id), ps); ps.println("DONE " + id); }
                catch (Exception e) { ps.println("FAILED: " + e.getMessage()); }
                finally { JOB.set(null); }
            }).start();
            ex.getResponseHeaders().add("Location", "/job");
            send(ex, 303, "text/plain", "started");
        });
        server.createContext("/job", ex -> {
            String running = JOB.get();
            String log = JOB_LOG.toString();
            Matcher m = Pattern.compile("DONE (\\S+)").matcher(log);
            String body = "<div class=card><pre style='white-space:pre-wrap'>" + esc(log.isEmpty() ? "(no job yet)" : log) + "</pre></div>"
                    + (m.find() ? "<p><a href='/r/" + m.group(1) + "/report.html'>Open the report</a></p>" : "") + "<p><a href='/'>Home</a></p>";
            send(ex, 200, "text/html; charset=utf-8", page("Simulation progress", (running != null ? "<meta http-equiv=refresh content=3>" : "") + body));
        });
        server.createContext("/r/", ex -> {
            String rel = URLDecoder.decode(ex.getRequestURI().getPath().substring(3), "UTF-8");
            Path p = resultsDir.resolve(rel).normalize();
            if (!p.startsWith(resultsDir.normalize()) || !Files.isRegularFile(p) || rel.contains("..")) { send(ex, 404, "text/plain", "not found"); return; }
            String type = rel.endsWith(".html") ? "text/html; charset=utf-8" : rel.endsWith(".csv") ? "text/csv; charset=utf-8" : "text/plain; charset=utf-8";
            byte[] b = Files.readAllBytes(p);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "not found"); return; }
            StringBuilder b = new StringBuilder();
            b.append("<p class=sub>Reproduction of AutoScaleSim (Aslanpour, Toosi, Taheri &amp; Gaire, SIMPAT 2021, ")
                    .append("<a href='https://doi.org/10.1016/j.simpat.2020.102245'>doi:10.1016/j.simpat.2020.102245</a>) with a Budget-Guarded, Explainable Auto-Scaling extension. ")
                    .append("Each run executes three real simulations of the NASA 7-day workload (about 30–60 s in total).</p>");
            b.append("<form class=card method=post action=/run><b>Run a comparison</b><br><br>")
                    .append("<label>Budget for the 7 simulated days (₹) <input name=budget type=number step=any min=1 value=2000 required></label><br><br>")
                    .append("<label>Response-time target (s) <input name=target type=number step=any min=0.01 value=4.0 required></label><br><br>")
                    .append("<label>Exchange-rate assumption (₹ per US$) <input name=rate type=number step=any min=0.01 value=" + DEFAULT_INR_PER_USD + " required></label><br><br>")
                    .append("<button type=submit>Run original + extension</button> ")
                    .append(JOB.get() != null ? "<a href='/job'>A job is running, see progress</a>" : "").append("</form>");
            b.append("<h2>Experiment history</h2><div class='card scroll'><table><tr><th>Run</th><th>Budget ₹</th><th>RT target s</th><th>Original renting ₹</th><th>Guarded renting ₹</th></tr>");
            List<Path> dirs = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(resultsDir)) { for (Path d : ds) if (Files.exists(d.resolve("meta.properties"))) dirs.add(d); }
            dirs.sort(Comparator.reverseOrder());
            for (Path d : dirs) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(d.resolve("meta.properties"))) { p.load(r); }
                String n = d.getFileName().toString();
                b.append("<tr><td><a href='/r/").append(n).append("/report.html'>").append(esc(n)).append("</a></td><td class=n>").append(p.getProperty("budget_inr"))
                        .append("</td><td class=n>").append(p.getProperty("target_rt_s")).append("</td><td class=n>").append(p.getProperty("original_renting_inr"))
                        .append("</td><td class=n>").append(p.getProperty("guard_renting_inr")).append("</td></tr>");
            }
            b.append("</table></div>");
            send(ex, 200, "text/html; charset=utf-8", page("AutoScaleSim + BudgetGuard", b.toString()));
        });
        server.start();
        System.out.println("BudgetGuard web interface on http://localhost:" + port + "/  (data: " + dataDir.toAbsolutePath() + ")");
    }

    static String page(String title, String body) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>" + esc(title)
                + "</title><style>" + Report.CSS + "</style></head><body><main><h1>" + esc(title) + "</h1>" + body + "</main></body></html>";
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static Map<String, String> form(String body) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    static double num(String s) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("All fields are required");
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("Not a number: " + s); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // 10. Self-tests
    // ------------------------------------------------------------------------------------------

    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static boolean throwsIAE(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static void unitTests() throws Exception {
        System.out.println("Unit tests");
        // configuration validation
        check("valid config accepted", !throwsIAE(() -> new GuardConfig(2000, 4, 85)));
        check("zero budget rejected", throwsIAE(() -> new GuardConfig(0, 4, 85)));
        check("negative budget rejected", throwsIAE(() -> new GuardConfig(-5, 4, 85)));
        check("NaN budget rejected", throwsIAE(() -> new GuardConfig(Double.NaN, 4, 85)));
        check("infinite budget rejected", throwsIAE(() -> new GuardConfig(Double.POSITIVE_INFINITY, 4, 85)));
        check("zero RT target rejected", throwsIAE(() -> new GuardConfig(2000, 0, 85)));
        check("negative exchange rate rejected", throwsIAE(() -> new GuardConfig(2000, 4, -1)));
        check("non-numeric form value rejected", throwsIAE(() -> num("abc")));
        check("empty form value rejected", throwsIAE(() -> num(" ")));
        check("budget converted to USD", Math.abs(new GuardConfig(850, 4, 85).budgetUsd() - 10) < 1e-9);

        // billing — same rule as the original simulator
        check("live VM 90 min billed 2 hours", Math.abs(Policy.vmChargeUsd(false, 0, 0, 5400, 0.04) - 0.08) < 1e-12);
        check("live VM just requested billed 1 hour", Math.abs(Policy.vmChargeUsd(false, 0, 100, 100, 0.04) - 0.04) < 1e-12);
        check("live VM exactly 1 hour billed 1 hour", Math.abs(Policy.vmChargeUsd(false, 0, 0, 3600, 0.04) - 0.04) < 1e-12);
        check("destroyed VM uses its final bill", Policy.vmChargeUsd(true, 0.12, 0, 99999, 0.04) == 0.12);

        // pacing
        double T = 7 * 24 * 3600.0;
        check("allowance at t=0 is one hour of budget", Math.abs(Policy.allowanceUsd(168, 0, T, 0) - 1.0) < 1e-9);
        check("allowance capped at full budget", Policy.allowanceUsd(168, T, T, 0) == 168);
        check("allowance keeps the minimum-fleet reserve", Math.abs(Policy.allowanceUsd(168, T, T, 0.04) - 167.96) < 1e-9);

        // decision rule
        int UP = Policy.UP, DOWN = Policy.DOWN, NO = Policy.NOTHING;
        Outcome o = Policy.decide(UP, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("affordable scale-up allowed", o.guardedDecision == UP && o.status == Status.OK);
        o = Policy.decide(UP, 1.0, 0.04, 2.0, 3, 1, 5.0, 4.0);
        check("affordable scale-up with missed target -> PERF_MISS", o.guardedDecision == UP && o.status == Status.PERF_MISS);
        o = Policy.decide(UP, 1.96, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("budget-limit is inclusive (exactly at allowance allowed)", o.guardedDecision == UP);
        o = Policy.decide(UP, 1.99, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("unaffordable scale-up held, target met -> BUDGET_HOLD", o.guardedDecision == NO && o.status == Status.BUDGET_HOLD);
        o = Policy.decide(UP, 1.99, 0.04, 2.0, 3, 1, 6.0, 4.0);
        check("unaffordable scale-up, target missed -> CONFLICT", o.guardedDecision == NO && o.status == Status.CONFLICT);
        o = Policy.decide(DOWN, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("scale-down always passes through", o.guardedDecision == DOWN && o.status == Status.OK);
        o = Policy.decide(NO, 2.5, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("over paced budget -> remove one VM (BUDGET_RECOVERY)", o.guardedDecision == DOWN && o.status == Status.BUDGET_RECOVERY && !o.budgetOk);
        o = Policy.decide(UP, 2.5, 0.04, 2.0, 3, 1, 6.0, 4.0);
        check("over budget while target missed -> CONFLICT with scale-down", o.guardedDecision == DOWN && o.status == Status.CONFLICT);
        o = Policy.decide(UP, 2.5, 0.04, 2.0, 1, 1, 6.0, 4.0);
        check("over budget at minimum fleet -> INFEASIBLE, no action", o.guardedDecision == NO && o.status == Status.INFEASIBLE);
        o = Policy.decide(NO, 1.0, 0.04, 2.0, 3, 1, 3.0, 4.0);
        check("no-op passes through", o.guardedDecision == NO && o.status == Status.OK);

        // explanations
        GuardConfig cfg = new GuardConfig(2000, 4, 85);
        DecisionRecord r = new DecisionRecord();
        r.originalDecision = UP; r.delayTime = 1.4; r.responseTime = 4.6; r.activeVms = 3; r.committedUsd = 1.99; r.allowanceUsd = 2.0; r.priceUsd = 0.04;
        Outcome oc = Policy.decide(UP, r.committedUsd, r.priceUsd, r.allowanceUsd, r.activeVms, 1, r.responseTime, cfg.targetRtSec);
        r.guardedDecision = oc.guardedDecision; r.status = oc.status; r.budgetOk = oc.budgetOk; r.perfOk = oc.perfOk;
        String e = explain(r, cfg, 1.0, 0.2, 1, true);
        check("explanation names the trigger and original action", e.contains("1.400 s") && e.contains("SCALE_UP"));
        check("explanation gives cost in INR", e.contains("₹172.55") && e.contains("₹170.00"));
        check("explanation reports missed target and conflict", e.contains("misses the 4.00 s target") && e.contains("WARNING"));
        check("analysis-only note appears in observe mode", explain(r, cfg, 1.0, 0.2, 1, false).contains("Analysis only"));
        r.status = Status.INFEASIBLE; r.budgetOk = false; r.activeVms = 1;
        check("infeasible explanation says no feasible configuration", explain(r, cfg, 1.0, 0.2, 1, true).contains("no feasible configuration"));

        // parsing of the original simulator's end-of-run report (text copied from a real run)
        String sample = "Total User requests : 567783.0\nCPU Utilization (%):35.799 Avg.   36.335 SD\nResponse Time (Sec.): 3.986 Avg.   4.464 SD\n"
                + "Delay Time (Sec.): 1.989 Avg.   4.462 SD\nCloudlet Canceled: 68\nCloudlet Failured: 9264\nSLA Violation: 29.617%\nMax used Vms: 8\n"
                + "Scale Up Decisions: 306\nScale Down Decisions: 304\nContradictory Scaling Decisions: 279\nProvisioned On-Demand Vm:306\n"
                + "De-Provisioned On-Demand Vm:304\n\u001B[31mCOST:...\nRenting Cost: $ 29.84\nSLA Penalty: $ 11.24\nTotal Cost : $ \u001B[31m......   41.08    .......";
        Map<String, Double> m = ConsoleParser.parse(sample);
        check("parser reads renting / penalty / total cost", m.get("renting_cost_usd") == 29.84 && m.get("sla_penalty_usd") == 11.24 && m.get("total_cost_usd") == 41.08);
        check("parser reads performance metrics", m.get("response_time_avg_s") == 3.986 && m.get("sla_violation_pct") == 29.617 && m.get("max_vms") == 8);
        check("parser reads all 16 metrics", m.size() == ConsoleParser.PATTERNS.length);

        // CSV round trip (explanations contain commas and quotes)
        Path tmp = Files.createTempDirectory("bg");
        Csv.write(tmp.resolve("t.csv"), Arrays.asList(new String[]{"a", "b"}, new String[]{"x, \"y\"", "₹1"}));
        List<String[]> back = Csv.read(tmp.resolve("t.csv"));
        check("CSV round trip keeps commas, quotes and the rupee sign", back.get(1)[0].equals("x, \"y\"") && back.get(1)[1].equals("₹1"));

        // report generation from synthetic results
        Comparison c = new Comparison();
        c.cfg = cfg; c.dir = tmp;
        c.original = fake("original", 29.84, 3.986); c.observe = fake("observe", 29.84, 3.986); c.guard = fake("guard", 20.0, 4.5);
        c.observe.decisions.add(DECISION_HEADER); c.guard.decisions.add(DECISION_HEADER);
        String[] row = {"600", "SATURDAY:00:10:00", "50", "1.2", "3.2", "2", "SCALE_UP", "DO_NOTHING", "true", "100", "120", "true", "true", "BUDGET_HOLD", "text, with comma"};
        c.observe.decisions.add(row); c.guard.decisions.add(row);
        Report.writeComparison(c);
        String html = new String(Files.readAllBytes(tmp.resolve("report.html")), StandardCharsets.UTF_8);
        check("report labels all three result types", html.contains("1. Original AutoScaleSim") && html.contains("2. Original, analysed by extension") && html.contains("3. Budget-guarded policy"));
        check("report flags missed RT target under budget", html.contains("cannot both be satisfied"));
        check("report includes explanation rows", html.contains("text, with comma") && html.contains("BUDGET_HOLD"));
    }

    static RunResult fake(String mode, double rentUsd, double rt) {
        RunResult r = new RunResult();
        r.mode = mode;
        r.metrics.put("renting_cost_usd", rentUsd); r.metrics.put("sla_penalty_usd", 1.0); r.metrics.put("total_cost_usd", rentUsd + 1);
        r.metrics.put("response_time_avg_s", rt); r.metrics.put("max_vms", 8.0);
        r.hourlyVms = new double[]{2, 3, 4}; r.hourlyRt = new double[]{3, 4, 5};
        return r;
    }

    static void integrationTests(Path dataDir, Path outDir) throws Exception {
        System.out.println("Integration tests (runs real simulations, about 1-2 minutes)");
        GuardConfig tight = new GuardConfig(1500, 4.0, DEFAULT_INR_PER_USD);
        RunResult orig = runChild("original", tight, dataDir, outDir.resolve("original"), System.out);
        RunResult obs = runChild("observe", tight, dataDir, outDir.resolve("observe"), System.out);
        String[] csvs = {"Monitor_VmHistory.csv", "Monitor_SLAHistory.csv", "Monitor_EndUserHistory.csv"};
        for (String f : csvs) {
            Path committed = dataDir.resolve(f);
            if (Files.exists(committed))
                check("original run reproduces the authors' committed " + f, sameFile(committed, orig.dir.resolve("src/others/" + f)));
            check("observe mode leaves " + f + " byte-identical to original", sameFile(orig.dir.resolve("src/others/" + f), obs.dir.resolve("src/others/" + f)));
        }
        check("observe mode summary metrics identical to original", orig.metrics.equals(obs.metrics));
        check("one decision record per scaling interval (7 days / 10 min = 1008)", obs.decisions.size() - 1 == 1008);
        double lastCommittedInr = Double.parseDouble(obs.decisions.get(obs.decisions.size() - 1)[9]);
        double printedInr = orig.metrics.get("renting_cost_usd") * DEFAULT_INR_PER_USD;
        check(String.format(Locale.ROOT, "extension's running cost estimate (₹%.2f) is consistent with the original's final renting cost (₹%.2f, within 2%%)", lastCommittedInr, printedInr),
                Math.abs(lastCommittedInr - printedInr) <= 0.02 * printedInr);

        GuardConfig huge = new GuardConfig(1e9, 1e9, DEFAULT_INR_PER_USD);
        RunResult loose = runChild("guard", huge, dataDir, outDir.resolve("guard_unlimited"), System.out);
        check("guard with unlimited budget changes nothing (identical VM history)", sameFile(orig.dir.resolve("src/others/Monitor_VmHistory.csv"), loose.dir.resolve("src/others/Monitor_VmHistory.csv")));

        RunResult g = runChild("guard", tight, dataDir, outDir.resolve("guard_1500"), System.out);
        double rc = g.metrics.get("renting_cost_usd") * DEFAULT_INR_PER_USD;
        Map<String, Integer> cnt = countStatus(g.decisions);
        check(String.format(Locale.ROOT, "guard keeps renting cost within ₹1500 budget (got ₹%.2f) or reports INFEASIBLE", rc), rc <= 1500 || cnt.getOrDefault("INFEASIBLE", 0) > 0);
        check("guard executed the same workload (same total requests)", orig.metrics.get("total_requests").equals(g.metrics.get("total_requests")));
    }

    /** Byte comparison that ignores '\r', because Git for Windows may check files out with CRLF line endings. */
    static boolean sameFile(Path a, Path b) throws IOException {
        if (!Files.exists(a) || !Files.exists(b)) return false;
        return new String(Files.readAllBytes(a), StandardCharsets.UTF_8).replace("\r", "")
                .equals(new String(Files.readAllBytes(b), StandardCharsets.UTF_8).replace("\r", ""));
    }

    // ------------------------------------------------------------------------------------------
    // 11. Small helpers
    // ------------------------------------------------------------------------------------------

    static final class Csv {
        static void write(Path f, List<String[]> rows) throws IOException {
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                for (String[] r : rows) {
                    for (int i = 0; i < r.length; i++) {
                        String v = r[i] == null ? "" : r[i];
                        if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
                        w.write(i > 0 ? "," + v : v);
                    }
                    w.write("\n");
                }
            }
        }

        static List<String[]> read(Path f) throws IOException {
            List<String[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
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
                    else if (ch == ',') { cells.add(cur.toString()); cur.setLength(0); }
                    else cur.append(ch);
                }
                cells.add(cur.toString());
                out.add(cells.toArray(new String[0]));
            }
            return out;
        }
    }

    static Map<String, Integer> countStatus(List<String[]> decisions) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 1; i < decisions.size(); i++) m.merge(decisions.get(i)[13], 1, Integer::sum);
        return m;
    }

    static double[] col(List<String[]> rows, int c) {
        double[] v = new double[Math.max(0, rows.size() - 1)];
        for (int i = 1; i < rows.size(); i++) v[i - 1] = Double.parseDouble(rows.get(i)[c]);
        return v;
    }

    static double[] div(double[] a, double d) { double[] o = new double[a.length]; for (int i = 0; i < a.length; i++) o[i] = a[i] / d; return o; }

    static double[] idx(double[] a) { double[] o = new double[a.length]; for (int i = 0; i < a.length; i++) o[i] = i + 1; return o; }

    static String fmt(double v, int digits) { return String.format(Locale.ROOT, "%." + digits + "f", v); }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;"); }

    static Map<String, String> args(String[] a, int from) {
        Map<String, String> m = new HashMap<>();
        for (int i = from; i < a.length; i++)
            if (a[i].startsWith("--")) m.put(a[i].substring(2), i + 1 < a.length && !a[i + 1].startsWith("--") ? a[++i] : "true");
        return m;
    }

    static Path findDataDir(Map<String, String> o) {
        if (o.containsKey("data")) return Paths.get(o.get("data")).toAbsolutePath();
        for (String c : new String[]{"src/others", "../src/others", "AutoScaleSim/src/others"})
            if (Files.exists(Paths.get(c, "Dataset_NASA_July.tset"))) return Paths.get(c).toAbsolutePath();
        throw new IllegalArgumentException("Cannot find Dataset_NASA_July.tset; run from the AutoScaleSim folder or pass --data <path to src/others>");
    }

    static GuardConfig cfgFrom(Map<String, String> o) {
        return new GuardConfig(Double.parseDouble(o.getOrDefault("budget-inr", "2000")),
                Double.parseDouble(o.getOrDefault("target-rt", "4.0")),
                Double.parseDouble(o.getOrDefault("inr-per-usd", String.valueOf(DEFAULT_INR_PER_USD))));
    }

    public static void main(String[] a) throws Exception {
        String cmd = a.length == 0 ? "help" : a[0];
        Map<String, String> o = args(a, 1);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        switch (cmd) {
            case "run": {
                String mode = o.getOrDefault("mode", "original");
                if (mode.equals("original")) ExperimentalSetup.main(new String[0]);   // the authors' entry point, untouched
                else if (mode.equals("observe") || mode.equals("guard")) runSetup(cfgFrom(o), mode.equals("guard"));
                else throw new IllegalArgumentException("--mode must be original, observe or guard");
                break;
            }
            case "compare":
                compare(cfgFrom(o), findDataDir(o), Paths.get(o.getOrDefault("out", "results/compare_" + stamp)), System.out);
                break;
            case "sweep": {
                String[] parts = o.getOrDefault("budgets", "750,1000,1500,2000,2500,3000").split(",");
                double[] b = new double[parts.length];
                for (int i = 0; i < b.length; i++) b[i] = Double.parseDouble(parts[i].trim());
                sweep(b, Double.parseDouble(o.getOrDefault("target-rt", "4.0")),
                        Double.parseDouble(o.getOrDefault("inr-per-usd", String.valueOf(DEFAULT_INR_PER_USD))),
                        findDataDir(o), Paths.get(o.getOrDefault("out", "results/sweep_" + stamp)), System.out);
                break;
            }
            case "test":
                unitTests();
                if (o.containsKey("integration")) integrationTests(findDataDir(o), Paths.get(o.getOrDefault("out", "results/test_" + stamp)));
                System.out.println("\n" + passed + " passed, " + failed + " failed");
                if (failed > 0) System.exit(1);
                break;
            case "serve":
                serve(Integer.parseInt(o.getOrDefault("port", System.getenv().getOrDefault("PORT", "8080"))),
                        findDataDir(o), Paths.get(o.getOrDefault("results", "results")).toAbsolutePath());
                break;
            default:
                System.out.println("Usage: BudgetGuard <run|compare|sweep|test|serve> [options]\n"
                        + "  compare --budget-inr 2000 --target-rt 4.0 [--inr-per-usd 85]\n"
                        + "  sweep   --budgets 750,1000,1500,2000,2500,3000 --target-rt 4.0\n"
                        + "  test    [--integration]\n"
                        + "  serve   [--port 8080]");
        }
    }
}
