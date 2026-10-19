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
import autoscalesim.applicationprovider.autoscaling.ExecutorSuperProfessional;
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
        return explain(r, cfg, "SLA_AWARE", 70, 40, dtUp, dtDown, minVms, enforce);
    }

    public static String explain(DecisionRecord r, GuardConfig cfg, String rule, double cpuUp, double cpuDown,
                                 double dtUp, double dtDown, int minVms, boolean enforce) {
        double rate = cfg.inrPerUsd;
        StringBuilder s = new StringBuilder();
        // (a) trigger and original action
        if (rule.equals("RESOURCE_AWARE")) {
            if (r.originalDecision == Policy.UP)
                s.append(String.format(Locale.ROOT, "Analysed CPU utilisation %.1f%% was above the scale-up threshold (%.0f%%), so the original resource-aware rule decided SCALE_UP. ", r.cpuUtil, cpuUp));
            else if (r.originalDecision == Policy.DOWN)
                s.append(String.format(Locale.ROOT, "Analysed CPU utilisation %.1f%% was below the scale-down threshold (%.0f%%), so the original rule decided SCALE_DOWN. ", r.cpuUtil, cpuDown));
            else
                s.append(String.format(Locale.ROOT, "Analysed CPU utilisation %.1f%% was between %.0f%% and %.0f%%, so the original rule decided DO_NOTHING. ", r.cpuUtil, cpuDown, cpuUp));
        } else if (!rule.equals("SLA_AWARE")) {
            s.append(String.format(Locale.ROOT, "With analysed CPU utilisation %.1f%% and delay time %.3f s, the original %s rule decided %s. ",
                    r.cpuUtil, r.delayTime, rule, Policy.decisionName(r.originalDecision)));
        } else if (r.originalDecision == Policy.UP)
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
        final double horizonSec, lookaheadSec, dtUp, dtDown, cpuUpT, cpuDownT;
        final String ruleName;
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
            this.cpuUpT = cpuUp;
            this.cpuDownT = cpuDown;
            this.ruleName = rule.name();
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
            r.explanation = explain(r, cfg, ruleName, cpuUpT, cpuDownT, dtUp, dtDown, minVms, enforce);
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
     * One simulation configuration. Defaults are copied from autoscalesim.ExperimentalSetup.main
     * (NASA, 7 days). The alternative values for the Wikipedia scenario are the ones the authors
     * documented in the comments of ExperimentalSetup.main ("NASA=..., Wikipedia=...").
     * The integration test proves that the default scenario, run through this runner in observe
     * mode, produces byte-identical output to the authors' own main().
     */
    public static final class Scenario {
        public String name = "NASA default (ExperimentalSetup.main)";
        public String dataset = "NASA";
        public int simMinutes = 7 * 1440;
        public int cloudletLength = 5000, pes = 2, cloudletTimeout = 30, minCloudletLength = 0, maxCloudletLength = 245000;
        public int initialVMs = 2, scalingInterval = 10;
        public double slaDelay = 1.0;
        public String analysis = "SIMPLE";
        public double sesAlphaCpu = 0.2, sesAlphaDelay = 0.1;
        public int timeWindow = 10;
        public String rule = "SLA_AWARE";
        public int vmConfig = AutoScaleSimTags.VM_CONFIG_T2MEDIUM;
        public double cpuUp = 70, cpuDown = 40, dtUp = 1, dtDown = 0.2;
        public String executor = "SIMPLE", surplus = "THE_OLDEST";
        public int cooldownMin = 0, maxVm = 40, minVm = 1;
        public double startupDelayMin = 5;

        public static Scenario nasaDefault() { return new Scenario(); }

        public static Scenario wikipediaDocumented() {
            Scenario s = new Scenario();
            s.name = "Wikipedia (values documented in ExperimentalSetup comments)";
            s.dataset = "WIKIPEDIA"; s.simMinutes = 4 * 60; s.pes = 1; s.cloudletTimeout = 50; s.initialVMs = 1;
            s.scalingInterval = 2; s.sesAlphaCpu = 0.1; s.timeWindow = 5; s.vmConfig = AutoScaleSimTags.VM_CONFIG_T2SMALL;
            s.dtUp = 0.140; s.dtDown = 0.130; s.startupDelayMin = 1; s.maxVm = 10;
            return s;
        }

        public boolean isNasaDefault() { return toProperties().equals(new Scenario().toProperties()); }

        public Properties toProperties() {
            Properties p = new Properties();
            for (java.lang.reflect.Field f : Scenario.class.getFields())
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !f.getName().equals("name"))
                    try { p.setProperty(f.getName(), String.valueOf(f.get(this))); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
            return p;
        }

        public static Scenario fromProperties(Properties p) {
            Scenario s = new Scenario();
            for (java.lang.reflect.Field f : Scenario.class.getFields()) {
                String v = p.getProperty(f.getName());
                if (v == null || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    if (f.getType() == int.class) f.setInt(s, Integer.parseInt(v.trim()));
                    else if (f.getType() == double.class) f.setDouble(s, Double.parseDouble(v.trim()));
                    else f.set(s, v);
                } catch (IllegalAccessException e) { throw new RuntimeException(e); }
            }
            s.validate();
            return s;
        }

        public void validate() {
            ScalingRule.valueOf(rule); ExecutorType.valueOf(executor); SurplusVMSelectionPolicy.valueOf(surplus);
            Method.valueOf(analysis); DATASET.valueOf(dataset);
            if (simMinutes < 1 || scalingInterval < 1 || initialVMs < 1 || minVm < 1 || maxVm < minVm || vmConfig < 0 || vmConfig > 3)
                throw new IllegalArgumentException("Invalid scenario values");
        }

        /** Nominal requests per minute one VM can complete at 100% CPU (from VM MIPS/PEs and request length). */
        public double vmCapacityPerMinute() {
            // CloudSim executes a request of length L on each of its PEs: one request needs L * pes MI in total.
            return 60.0 * AutoScaleSimTags.VM_MIPS[vmConfig] * AutoScaleSimTags.VM_PES[vmConfig] / ((double) cloudletLength * pes);
        }

        public Scenario copy() { Scenario s = fromProperties(toProperties()); s.name = name; return s; }
    }

    static Scenario readScenarioFile(Path f) throws IOException {
        if (!Files.exists(f)) return Scenario.nasaDefault();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
        Scenario s = Scenario.fromProperties(p);
        s.name = p.getProperty("name", s.name);
        return s;
    }

    static void writeScenarioFile(Scenario s, Path f) throws IOException {
        Properties p = s.toProperties();
        p.setProperty("name", s.name);
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) { p.store(w, "AutoScaleSim scenario"); }
    }

    /** Builds the same entities, in the same order, as ExperimentalSetup.main, with values from the scenario. */
    static void runSetup(GuardConfig cfg, boolean enforce, Scenario sc) throws Exception {
        System.out.println("Starting AutoScaleSim Simulation... (BudgetGuard " + (enforce ? "guard" : "observe") + " mode, " + sc.name + ")");
        call("cloudSimSetup", new Class<?>[]{});

        final int SIMULATION_LIMIT = sc.simMinutes;
        DATASET datasetType = DATASET.valueOf(sc.dataset);

        LoadAdmission loadAdmission = new LoadAdmission();
        LoadBalancing loadBalancing = new LoadBalancingRoundRobin();
        int cloudletSchedulerName = AutoScaleSimTags.TimeShared;
        Monitor monitor = new Monitor();
        String[] analysisMethod = new String[10];
        Arrays.fill(analysisMethod, Method.valueOf(sc.analysis).name());
        double[] sESAlpha = {sc.sesAlphaCpu, 0, 0, 0, sc.sesAlphaDelay, 0, 0, 0, 0, 0};
        Analyzer analyzer = new Analyzer(analysisMethod, sc.timeWindow, sESAlpha);

        GuardedPlanner planner = new GuardedPlanner(ScalingRule.valueOf(sc.rule), sc.vmConfig, sc.cpuUp, sc.cpuDown,
                sc.dtUp, sc.dtDown, cfg, enforce, SIMULATION_LIMIT * (double) AutoScaleSimTags.aMinute,
                sc.scalingInterval * (double) AutoScaleSimTags.aMinute, sc.minVm);

        final ExecutorType executorType = ExecutorType.valueOf(sc.executor);
        final SurplusVMSelectionPolicy surplus = SurplusVMSelectionPolicy.valueOf(sc.surplus);
        final int COOLDOWN = sc.cooldownMin * AutoScaleSimTags.aMinute;

        call("createCloudProvider", new Class<?>[]{int.class}, sc.cloudletTimeout);
        String startUpDelayType = "Static";
        final double BASE_DELAY_IN_VM_START_UP = sc.startupDelayMin * AutoScaleSimTags.aMinute;
        Executor executor = new ExecutorSimple(executorType, surplus, COOLDOWN, sc.maxVm, sc.minVm,
                startUpDelayType, BASE_DELAY_IN_VM_START_UP);
        if (executorType == ExecutorType.SUPREX)
            executor = new ExecutorSuperProfessional(executorType, surplus, COOLDOWN, sc.maxVm, sc.minVm,
                    startUpDelayType, BASE_DELAY_IN_VM_START_UP);
        final String[] reports = new String[]{"M_VM", "M_User", "M_SLA", "", "", ""};

        call("createEndUserEmulator",
                new Class<?>[]{String.class, int.class, DATASET.class, int.class, int.class, int.class, int.class},
                "EndUserEmulator", SIMULATION_LIMIT, datasetType, sc.cloudletLength, sc.pes, sc.minCloudletLength, sc.maxCloudletLength);
        call("createApplicationProvider",
                new Class<?>[]{String.class, int.class, DATASET.class, LoadAdmission.class, LoadBalancing.class, int.class,
                        int.class, double.class, Monitor.class, Analyzer.class, Planner.class, Executor.class, String[].class},
                "ApplicationProvider", sc.initialVMs, datasetType, loadAdmission, loadBalancing, cloudletSchedulerName,
                sc.scalingInterval, sc.slaDelay, monitor, analyzer, planner, executor, reports);

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
        return runChild(mode, cfg, Scenario.nasaDefault(), dataDir, runDir, null, progress);
    }

    /**
     * Runs one simulation in a fresh JVM whose working directory is runDir (the original code reads and
     * writes the relative folder "src/others/"). If workloadTset is given, it is placed where the original
     * EndUserEmulator loads the NASA trace from, so the unmodified emulator replays the user's workload.
     */
    static RunResult runChild(String mode, GuardConfig cfg, Scenario sc, Path dataDir, Path runDir, Path workloadTset,
                              PrintStream progress) throws Exception {
        if (mode.equals("original") && (!sc.isNasaDefault() || workloadTset != null))
            throw new IllegalArgumentException("mode 'original' runs the authors' unmodified main(), which only supports the default NASA scenario; use 'observe'");
        Path others = runDir.resolve("src").resolve("others");
        Files.createDirectories(others);
        Path tset = others.resolve("Dataset_NASA_July.tset");
        if (workloadTset != null) Files.copy(workloadTset, tset, StandardCopyOption.REPLACE_EXISTING);
        else if (sc.dataset.equals("NASA")) linkOrCopy(dataDir.resolve("Dataset_NASA_July.tset"), tset);
        if (sc.dataset.equals("WIKIPEDIA"))
            linkOrCopy(dataDir.resolve("Dataset_Wikipedia_4hour.csv"), others.resolve("Dataset_Wikipedia_4hour.csv"));
        writeScenarioFile(sc, runDir.resolve("scenario.properties"));

        String javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        StringBuilder cp = new StringBuilder();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator))
            if (!e.isEmpty()) cp.append(cp.length() > 0 ? File.pathSeparator : "").append(Paths.get(e).toAbsolutePath());
        String heap = System.getenv().getOrDefault("SIM_HEAP", "1g");   // a 7-day NASA run peaks at about 550 MB
        if (!heap.matches("\\d+[mMgG]")) heap = "1g";
        List<String> cmd = new ArrayList<>(Arrays.asList(javaExe, "-Xmx" + heap, "-Duser.language=en", "-Duser.country=US",
                "-cp", cp.toString(), BudgetGuard.class.getName(), "run", "--mode", mode,
                "--budget-inr", String.valueOf(cfg.budgetInr), "--target-rt", String.valueOf(cfg.targetRtSec),
                "--inr-per-usd", String.valueOf(cfg.inrPerUsd)));
        if (progress != null) progress.println("  running " + mode + " simulation (" + sc.name + ") ...");
        long t0 = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).directory(runDir.toFile()).redirectErrorStream(true)
                .redirectOutput(runDir.resolve("console.log").toFile()).start();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException(mode + " simulation timed out after 30 minutes");
        }
        int code = p.exitValue();
        RunResult rr = collect(mode, runDir);
        rr.exitCode = code;
        rr.seconds = (System.currentTimeMillis() - t0) / 1000;
        if (code != 0 || rr.metrics.get("renting_cost_usd") == null)
            throw new IllegalStateException(mode + " simulation failed (exit " + code + "); see " + runDir.resolve("console.log"));
        if (progress != null) progress.println("  " + mode + " finished in " + rr.seconds + " s");
        return rr;
    }

    /**
     * Replays an uploaded workload through AutoScaleSim: the default NASA scenario with the trace replaced by
     * the workload (converted to the original DataSet format) and the simulation length set to its length.
     * The "observe" run executes the original policy; the optional "guard" run executes the budget guard.
     */
    static List<RunResult> simulateWorkload(Workload w, GuardConfig cfg, boolean withGuard, Path dataDir, Path outDir,
                                            PrintStream progress) throws Exception {
        Files.createDirectories(outDir);
        Path tset = outDir.resolve("workload.tset");
        w.writeTset(tset);
        Workload.writeCsv(outDir.resolve("workload.csv"), w.perMinute, w.name);
        Scenario sc = Scenario.nasaDefault();
        sc.name = "Uploaded workload: " + w.name;
        sc.simMinutes = w.perMinute.length;
        List<RunResult> out = new ArrayList<>();
        out.add(runChild("observe", cfg, sc, dataDir, outDir.resolve("original_policy"), tset, progress));
        if (withGuard) out.add(runChild("guard", cfg, sc, dataDir, outDir.resolve("guard"), tset, progress));
        return out;
    }

    /** Writes the demo CSVs. Real data comes from the authors' NASA trace; synthetic data is labelled as such. */
    static void writeSamples(Path dataDir, Path out) throws IOException {
        Files.createDirectories(out);
        Path tset = dataDir.resolve("Dataset_NASA_July.tset");
        // week 2 of the NASA trace (days 8-14): real data that the default 7-day experiment never uses
        double[] week2 = Workload.nasaMinutes(tset, 7 * 1440, 14 * 1440);
        Workload.writeCsv(out.resolve("nasa_week2_per_minute.csv"), week2, "NASA trace days 8-14");
        // 3 days of NASA days 8-10 with a SYNTHETIC flash crowd (x3 for 40 minutes on day 3, 14:00) for demonstrations
        double[] flash = Workload.nasaMinutes(tset, 7 * 1440, 10 * 1440);
        int start = 2 * 1440 + 14 * 60;
        for (int i = 0; i < 40; i++) {
            double ramp = i < 10 ? 1 + 0.2 * (i + 1) : 3;          // 10-minute ramp up to x3
            flash[start + i] = Math.min(Workload.MAX_PER_MINUTE, Math.round(flash[start + i] * ramp));
        }
        Workload.writeCsv(out.resolve("synthetic_flash_crowd_3days.csv"), flash, "synthetic");
        // a small request-log example (synthetic): one row per request with an ISO timestamp
        StringBuilder log = new StringBuilder("timestamp,path\n");
        java.util.Random rnd = new java.util.Random(42);
        long t0 = java.time.LocalDateTime.of(2026, 1, 5, 9, 0).toEpochSecond(java.time.ZoneOffset.UTC);
        for (int m = 0; m < 180; m++) {
            int n = 20 + (int) (15 * Math.sin(m / 30.0)) + (m >= 120 && m < 135 ? 60 : 0) + rnd.nextInt(5);
            for (int i = 0; i < n; i++)
                log.append(java.time.Instant.ofEpochSecond(t0 + m * 60L + (i * 60L) / n)).append(",/index.html\n");
        }
        Files.write(out.resolve("synthetic_request_log_3h.csv"), log.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolve("README.md"), ("# Sample workloads\n\n"
                + "| File | Source | Format |\n|---|---|---|\n"
                + "| nasa_week2_per_minute.csv | **Real**: NASA trace shipped with AutoScaleSim (`src/others/Dataset_NASA_July.tset`), days 8-14, not used by the default 7-day experiment | per-minute counts |\n"
                + "| synthetic_flash_crowd_3days.csv | **Synthetic**: NASA days 8-10 with an injected flash crowd (10-minute ramp to 3x, 40 minutes, day 3 at 14:00) | per-minute counts |\n"
                + "| synthetic_request_log_3h.csv | **Synthetic**: generated request log (seed 42), one row per request, ISO-8601 timestamps | request log |\n\n"
                + "Regenerate with `BudgetGuard samples`.\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Samples written to " + out.toAbsolutePath());
    }

    /** Lists or deletes runs and workloads in the dashboard database (DATA_DIR, or DATABASE_URL for PostgreSQL). */
    static void history(String[] a, Map<String, String> o) throws Exception {
        String sub = a.length > 1 ? a[1] : "list";
        String arg = a.length > 2 && !a[2].startsWith("--") ? a[2] : null;
        Path dataDir = Paths.get(o.getOrDefault("data-dir", System.getenv().getOrDefault("DATA_DIR", "data")));
        try (Store store = Store.open(dataDir)) {
            switch (sub) {
                case "list":
                    System.out.println("Workloads (" + store.count("workloads") + "):");
                    for (Map<String, Object> w : store.listWorkloads())
                        System.out.println("  " + w.get("id") + "  " + w.get("uploaded_at") + "  " + w.get("name"));
                    System.out.println("Runs (" + store.count("runs") + "):");
                    for (Map<String, Object> r : store.listRuns(500))
                        System.out.println("  " + r.get("id") + "  " + r.get("created_at") + "  " + r.get("kind") + "  " + r.get("status") + "  " + r.get("params"));
                    break;
                case "delete-workload":
                    if (arg == null) throw new IllegalArgumentException("history delete-workload <id>");
                    System.out.println(store.deleteWorkload(arg) ? "Deleted workload " + arg : "No workload " + arg);
                    break;
                case "delete-workloads": {
                    String prefix = o.get("name-prefix");
                    if (prefix == null || prefix.isEmpty()) throw new IllegalArgumentException("history delete-workloads --name-prefix <text>");
                    int n = 0;
                    for (Map<String, Object> w : store.listWorkloads())
                        if (String.valueOf(w.get("name")).startsWith(prefix) && store.deleteWorkload((String) w.get("id"))) n++;
                    System.out.println("Deleted " + n + " workload(s) whose name starts with '" + prefix + "'");
                    break;
                }
                case "delete-run":
                    if (arg == null) throw new IllegalArgumentException("history delete-run <id>");
                    deleteRunFiles(store, arg, dataDir);
                    break;
                case "clear-runs":
                    for (Map<String, Object> r : store.listRuns(500)) deleteRunFiles(store, (String) r.get("id"), dataDir);
                    break;
                default:
                    throw new IllegalArgumentException("history list | delete-run <id> | delete-workload <id> | delete-workloads --name-prefix <text> | clear-runs");
            }
        }
    }

    static void deleteRunFiles(Store store, String id, Path dataDir) throws Exception {
        String dir = store.deleteRun(id);
        if (dir == null) { System.out.println("No run " + id); return; }
        Path results = dataDir.resolve("results").toAbsolutePath().normalize();
        // only ever delete inside this database's own results folder
        if (!dir.isEmpty() && Paths.get(dir).toAbsolutePath().normalize().startsWith(results) && Files.isDirectory(Paths.get(dir)))
            try (java.util.stream.Stream<Path> w = Files.walk(Paths.get(dir))) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        System.out.println("Deleted run " + id);
    }

    static void linkOrCopy(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) return;
        if (!Files.exists(src)) throw new FileNotFoundException("Dataset not found: " + src.toAbsolutePath());
        try { Files.createLink(dst, src.toAbsolutePath()); } catch (Exception e) { Files.copy(src, dst); }
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
    // 9. Shared helpers
    // ------------------------------------------------------------------------------------------

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
                else if (mode.equals("observe") || mode.equals("guard"))
                    runSetup(cfgFrom(o), mode.equals("guard"), readScenarioFile(Paths.get("scenario.properties")));
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
            case "history":
                history(a, o);
                break;
            case "evaluate":
                Evaluate.run(findDataDir(o), Paths.get(o.getOrDefault("out", "docs/results")), System.out);
                break;
            case "samples":
                writeSamples(findDataDir(o), Paths.get(o.getOrDefault("out", "samples")));
                break;
            case "analyse": {
                Workload w = Workload.parse(Paths.get(o.get("csv")).getFileName().toString(),
                        new String(Files.readAllBytes(Paths.get(o.get("csv"))), StandardCharsets.UTF_8));
                Workload.Params p = new Workload.Params();
                if (o.containsKey("k")) p.k = Double.parseDouble(o.get("k"));
                System.out.println(Json.write(w.analyse(p).summary));
                break;
            }
            case "simulate": {
                Workload w = Workload.parse(Paths.get(o.get("csv")).getFileName().toString(),
                        new String(Files.readAllBytes(Paths.get(o.get("csv"))), StandardCharsets.UTF_8));
                List<RunResult> rs = simulateWorkload(w, cfgFrom(o), o.containsKey("guard"), findDataDir(o),
                        Paths.get(o.getOrDefault("out", "results/workload_" + stamp)), System.out);
                for (RunResult r : rs) System.out.println(r.mode + " " + Json.write(r.metrics));
                break;
            }
            case "serve":
                WebApp.main(Arrays.copyOfRange(a, 1, a.length));
                Thread.currentThread().join();
                break;
            default:
                System.out.println("Usage: BudgetGuard <command> [options]\n"
                        + "  serve    [--port 8080] [--data-dir data]           web dashboard + API\n"
                        + "  compare  --budget-inr 2000 --target-rt 4.0 [--inr-per-usd 85]\n"
                        + "  sweep    --budgets 750,1000,1500,2000,2500,3000 --target-rt 4.0\n"
                        + "  samples  [--out samples]                            regenerate sample workloads\n"
                        + "  analyse  --csv file.csv [--k 2.5]                   forecast and spike analysis\n"
                        + "  simulate --csv file.csv [--guard] [--budget-inr N]  replay a workload in AutoScaleSim\n"
                        + "  evaluate [--out docs/results]                       run every experiment in the report\n"
                        + "  history  list | delete-run <id> | delete-workload <id> | delete-workloads --name-prefix qa_ | clear-runs\n"
                        + "           [--data-dir data]                          housekeeping of the dashboard database\n"
                        + "  run      --mode original|observe|guard              one simulation (internal)");
        }
    }
}
