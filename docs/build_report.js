// Generates docs/AutoScaleSim_Lab_Report.docx from the evaluation CSVs and figures.
// Run: node docs/build_report.js   (needs the "docx" npm package)
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, Table, TableRow, TableCell, WidthType,
  ShadingType, ImageRun, PageBreak, LevelFormat, Footer, PageNumber, TableOfContents, BorderStyle
} = require('docx');

const ROOT = path.join(__dirname, '..');
const R = (f) => path.join(ROOT, 'docs', 'results', f);
const csv = (f) => fs.readFileSync(R(f), 'utf8').trim().split('\n').map(splitCsv);
function splitCsv(line) {
  const out = []; let cur = '', q = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (q) { if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (c === '"') q = false; else cur += c; }
    else if (c === '"') q = true; else if (c === ',') { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur); return out;
}

const FONT = 'Calibri';
const W = 9026; // A4 text width in DXA with 1-inch margins
const p = (text, opts = {}) => new Paragraph({ spacing: { after: 120, line: 300 }, alignment: opts.align, children: runs(text, opts) });
function runs(text, opts = {}) {
  // **bold** segments
  return String(text).split(/(\*\*[^*]+\*\*)/).filter(Boolean).map((s) => s.startsWith('**')
    ? new TextRun({ text: s.slice(2, -2), bold: true, font: FONT, size: opts.size || 22 })
    : new TextRun({ text: s, font: FONT, size: opts.size || 22, italics: opts.italics, color: opts.color }));
}
const h1 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_1, spacing: { before: 240, after: 120 }, children: [new TextRun({ text: t, font: FONT })] });
const h2 = (t) => new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 200, after: 100 }, children: [new TextRun({ text: t, font: FONT })] });
const bullet = (t) => new Paragraph({ numbering: { reference: 'bullets', level: 0 }, spacing: { after: 60 }, children: runs(t) });
const num = (t) => new Paragraph({ numbering: { reference: 'numbers', level: 0 }, spacing: { after: 60 }, children: runs(t) });
const caption = (t) => new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 }, children: [new TextRun({ text: t, italics: true, font: FONT, size: 19, color: '52514E' })] });
const pageBreak = () => new Paragraph({ children: [new PageBreak()] });

function figure(file, widthPx, caption_) {
  const full = path.join(ROOT, 'docs', file);
  const buf = fs.readFileSync(full);
  const w = buf.readUInt32BE(16), h = buf.readUInt32BE(20); // PNG header
  const width = widthPx, height = Math.round(widthPx * h / w);
  return [new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 },
    children: [new ImageRun({ type: 'png', data: buf, transformation: { width, height }, altText: { title: caption_, description: caption_, name: path.basename(file) } })] }),
  caption(caption_)];
}

function table(rows, widths, opts = {}) {
  const total = widths.reduce((a, b) => a + b, 0);
  const border = { style: BorderStyle.SINGLE, size: 4, color: 'D9D8D3' };
  const borders = { top: border, bottom: border, left: border, right: border };
  return new Table({
    width: { size: total, type: WidthType.DXA }, columnWidths: widths,
    rows: rows.map((r, i) => new TableRow({
      tableHeader: i === 0,
      children: r.map((c, j) => new TableCell({
        width: { size: widths[j], type: WidthType.DXA }, borders,
        shading: i === 0 ? { type: ShadingType.CLEAR, color: 'auto', fill: 'EEF3FB' } : undefined,
        margins: { top: 60, bottom: 60, left: 90, right: 90 },
        children: [new Paragraph({ alignment: (j > 0 && opts.numeric && i > 0) ? AlignmentType.RIGHT : AlignmentType.LEFT,
          children: [new TextRun({ text: String(c), bold: i === 0, font: FONT, size: opts.size || 18 })] })]
      }))
    }))
  });
}
const spacer = () => new Paragraph({ spacing: { after: 120 }, children: [] });

// ------------------------------------------------------------------ data
const e1 = csv('e1_reproduction.csv');
const e2 = csv('e2_budget_sweep.csv');
const e3 = csv('e3_replay_fidelity.csv');
const e4 = csv('e4_forecast_backtest.csv');
const e5 = csv('e5_capacity_estimate.csv');
const m = Object.fromEntries(e1.slice(1).map((r) => [r[0], r[1]]));
const wk = Object.fromEntries(e1.slice(1).map((r) => [r[0], r[2]]));
const g2000 = e2.find((r) => r[1] === '2000');
const label = {
  total_requests: 'Requests', response_time_avg_s: 'Avg response time (s)', delay_time_avg_s: 'Avg delay time (s)',
  sla_violation_pct: 'SLA violation (%)', cpu_util_avg_pct: 'Avg CPU utilisation (%)', max_vms: 'Max VMs',
  vms_provisioned: 'VMs provisioned', contradictory_decisions: 'Contradictory decisions', cloudlets_failed: 'Failed requests',
  renting_cost_usd: 'Renting cost (US$)', sla_penalty_usd: 'SLA penalty (US$)', total_cost_usd: 'Total cost (US$)'
};

// ------------------------------------------------------------------ content
const c = [];
// Title page
c.push(new Paragraph({ spacing: { before: 2400, after: 240 }, alignment: AlignmentType.CENTER,
  children: [new TextRun({ text: 'Reproduction and Extension of AutoScaleSim for Budget-Aware, Explainable and Predictive Cloud Auto-Scaling', bold: true, size: 40, font: FONT })] }));
c.push(p('Cloud Computing — Project Report', { align: AlignmentType.CENTER, size: 26 }));
c.push(spacer());
c.push(p('Base paper: M. S. Aslanpour, A. N. Toosi, J. Taheri and R. Gaire, "AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds," Simulation Modelling Practice and Theory, vol. 108, art. 102245, 2021.', { align: AlignmentType.CENTER, size: 20, italics: true }));
c.push(spacer()); c.push(spacer());
c.push(p('Student name: ______________________________', { align: AlignmentType.CENTER }));
c.push(p('Roll number: ______________________________', { align: AlignmentType.CENTER }));
c.push(p('Course / instructor: ______________________________', { align: AlignmentType.CENTER }));
c.push(p('October 2026', { align: AlignmentType.CENTER }));
c.push(pageBreak());

c.push(new Paragraph({ children: [new TextRun({ text: 'Contents', bold: true, size: 28, font: FONT })] }));
c.push(new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-2' }));
c.push(p('(In Word: right-click the table of contents and choose Update Field to fill in page numbers.)', { size: 18, italics: true, color: '52514E' }));
c.push(pageBreak());

// Abstract
c.push(h1('Abstract'));
c.push(p(`Auto-scaling lets a cloud-hosted Web application add or remove virtual machines (VMs) as demand changes, but rule-based auto-scalers optimise performance without knowing the owner's budget and give no explanation for their decisions. This project reproduces AutoScaleSim, a published Java simulation toolkit for auto-scaling Web applications (Aslanpour et al., 2021), from the authors' original source code and then extends it in three ways without modifying the original algorithms. First, the original NASA experiment was run unchanged and its three monitoring outputs are byte-identical to the files committed by the authors (${m.total_requests} requests, average response time ${m.response_time_avg_s} s, renting cost US$${m.renting_cost_usd}). Second, a budget guard, implemented as a subclass of the original rule-based planner, paces spending against a user budget in Indian rupees, explains every scaling decision in plain English, and warns when the budget and a response-time target cannot both be met. Across budgets of ₹750–₹3000 it kept renting cost within budget in every feasible case, at the price of higher response time and SLA penalties (for example ₹${g2000[2]} and ${g2000[5]} s at a ₹2000 budget versus ₹2536.40 and ${m.response_time_avg_s} s for the original). Third, users can upload their own workload CSV, which is replayed by the unmodified simulator, forecast with Holt-Winters exponential smoothing, screened for traffic spikes, and converted into capacity and cost estimates. The system is delivered as a Web dashboard with a database, packaged with Docker, and deployable to AWS EC2 with a CloudFormation template. All 46 automated tests pass.`));

// 1 Introduction
c.push(h1('1. Introduction'));
c.push(h2('1.1 Problem statement and motivation'));
c.push(p('Web applications in Infrastructure-as-a-Service clouds pay for VMs by the hour while their demand varies by time of day and can spike without warning. Auto-scalers follow a Monitor–Analyse–Plan–Execute (MAPE) loop to keep performance acceptable, but common rule-based policies react only to utilisation or latency. They do not know how much the owner can spend, so a student project, a start-up or a public service with a fixed monthly allowance cannot express "stay within ₹2000 this week". They also do not explain why a VM was added or removed, which makes it hard to trust or audit them.'));
c.push(p('Studying these questions on real clouds is expensive and not repeatable. Simulation is the standard alternative, and AutoScaleSim is a toolkit built for exactly this setting: it extends CloudSim with the components needed to host and auto-scale a Web application, and it ships with real NASA and Wikipedia request traces.'));
c.push(h2('1.2 Objectives'));
['Reproduce the AutoScaleSim implementation from the authors\' repository and verify the reproduction objectively.',
  'Add budget-aware, explainable auto-scaling: user budget and response-time target, decision explanations, cost/performance comparison, and warnings when the constraints conflict.',
  'Add custom workload upload with traffic visualisation, documented forecasting, spike warnings, and capacity and cost estimates.',
  'Keep original results and extension results separate, and compare them fairly on identical workloads.',
  'Deliver a working Web dashboard with a database, deployed on a cloud platform, with automated tests and documentation.'].forEach((t) => c.push(num(t)));
c.push(h2('1.3 What is original and what is added'));
c.push(table([
  ['Item', 'Author', 'Status in this project'],
  ['AutoScaleSim simulator, scaling rules, executors, cost model, NASA/Wikipedia traces', 'Aslanpour et al.', 'Used unchanged (src/)'],
  ['Running the original code with modern Java; output identity check', 'This project', 'Reproduced and verified'],
  ['Budget guard, explanations, conflict warnings', 'This project', 'Implemented, tested, evaluated'],
  ['Workload upload, replay, forecasting, spike warnings, capacity/cost', 'This project', 'Implemented, tested, evaluated'],
  ['Dashboard, API, database, Docker, AWS template', 'This project', 'Implemented and tested'],
], [4200, 1800, 3026]));
c.push(spacer());
c.push(p('**Novelty is not claimed** at the research-literature level. Cost-aware and budget-constrained auto-scaling, explainable decision support and workload forecasting are all studied in the literature. The contribution is an integration of these ideas with AutoScaleSim, whose code base has a cost model and cost-aware VM-removal policies but no user budget, no decision explanations and no workload upload.'));

// 2 Literature review
c.push(h1('2. Background and literature review'));
c.push(h2('2.1 Auto-scaling and the MAPE loop'));
c.push(p('Autonomic computing describes self-managing systems that monitor themselves, analyse what they observe, plan a change and execute it [5]. Surveys of cloud auto-scaling classify techniques into threshold rules, queueing models, control theory, reinforcement learning and time-series forecasting, and note that threshold rules dominate in practice because they are simple to configure [3], [4]. Qu, Calheiros and Buyya organise Web-application auto-scaling around the same MAPE phases and identify cost, SLA and the start-up delay of VMs as key concerns [4].'));
c.push(h2('2.2 Simulation: CloudSim and AutoScaleSim'));
c.push(p('CloudSim is a discrete-event toolkit for modelling data centres, hosts, VMs and workloads [2]. AutoScaleSim builds on it to address a gap its authors describe: existing simulators did not support hosting, deploying and auto-scaling Web applications [1]. The toolkit lets users implement each MAPE phase separately, evaluate load-balancing algorithms, and replay the NASA and Wikipedia traces [1]. The NASA trace is the well-known July 1995 Kennedy Space Center Web log characterised by Arlitt and Williamson [9].'));
c.push(h2('2.3 Policies available in the AutoScaleSim code'));
c.push(p('The following were identified by reading the source code (ExperimentalSetup.java and the autoscaling package). They are the building blocks the extension reuses:'));
['**Analyser** methods: SIMPLE (latest value), moving average, weighted moving average, Fibonacci-weighted moving average, single exponential smoothing.',
  '**Planner** rules (PlannerRuleBased): RESOURCE_AWARE (CPU thresholds), SLA_AWARE (delay thresholds, the default), HYBRID, and four alarm-based rules (UT_1Al, UT_2Al, LAT_1Al, LAT_2Al).',
  '**Executors**: SIMPLE (Amazon-EC2-like) and SUPREX; surplus-VM selection: random, oldest (default), youngest, cloudlet-aware, load-aware and two cost-aware variants.',
  '**Cost model**: each on-demand VM is billed ceil(hours alive) × hourly price (t2.medium US$0.04/h, Sydney Linux prices in AutoScaleSimTags); SLA penalty is charged per violated hour at the VM price.',
  '**Default experiment**: NASA trace, 7 days, 2 initial VMs, scaling every 10 minutes, SLA-aware rule (scale up if delay > 1 s, down if < 0.2 s), t2.medium, 1–40 VMs, 5-minute start-up delay.'].forEach((t) => c.push(bullet(t)));
c.push(h2('2.4 Budgets, explanations and forecasting'));
c.push(p('Cloud providers expose budgets as monitoring and alerting (for example AWS Budgets) rather than as an input to the auto-scaler. Explainability research argues that automated decisions should be accompanied by reasons a human can check [11]. For prediction, exponential smoothing is a long-established forecasting family: Holt added a trend term [6], Winters a seasonal term [7], and Gardner and McKenzie a damped trend that avoids over-extrapolation [8]. These methods are simple, fast and transparent, which suits a decision-support tool that must explain itself.'));
c.push(h2('2.5 Gap addressed by this project'));
c.push(p('Within AutoScaleSim the user can measure cost after a run but cannot constrain it, cannot see why each decision was made, and can only use the two built-in traces. This project adds those three capabilities around the original simulator instead of inside it, so the original behaviour remains available for comparison.'));

// 3 Reproduction
c.push(h1('3. Reproduction of the original implementation'));
c.push(h2('3.1 Environment and compatibility fixes'));
c.push(p('The authors\' repository (commit 9a0febb) contains a NetBeans/Ant project targeting Java 8, the sources, the NASA trace as a serialised Neuroph DataSet, the Wikipedia trace and three output CSVs from a run. The required JARs were not included; the project referenced them on the authors\' disk. The smallest compatible fix was used:'));
['Libraries from Maven Central: commons-math3 3.5 and POI 3.8 (the exact versions named by the project). Neuroph 2.9 is not on Maven Central, so the repackaged org.beykery:neuroph:2.92 was used.',
  'The original sources compiled unchanged with JDK 21 using --release 8 (the project\'s javac.source/target).',
  'Because the code reads and writes the relative folder src/others/ and keeps static state, each simulation is run in its own JVM and working folder.'].forEach((t) => c.push(bullet(t)));
c.push(h2('3.2 Verification'));
c.push(p('The authors\' entry point autoscalesim.ExperimentalSetup.main() was executed without modification. The three monitoring files it writes (per-minute VM, SLA and end-user histories, 10,080 rows each) were compared with the files the authors committed to their repository. **All three are byte-identical** (comparison ignores Windows line endings). This is strong evidence that the reproduction, including the Neuroph substitute, behaves exactly as the authors\' own run.'));
c.push(table([['Metric (AutoScaleSim end-of-run report)', 'NASA, 7 days (authors\' main)', 'Wikipedia, 4 h (documented values)']]
  .concat(e1.slice(1).map((r) => [label[r[0]] || r[0], r[1], r[2]])), [4000, 2513, 2513], { numeric: true }));
c.push(caption('Table 1. Reproduced results. The Wikipedia column uses the values the authors wrote in comments of ExperimentalSetup.main; it is run through this project\'s runner because main() is hard-coded to NASA.'));
c.push(h2('3.3 Comparison with the paper'));
c.push(p('The paper\'s PDF could not be downloaded from the build environment because of its network policy, so the paper\'s reported figures have not yet been transcribed and compared. The reproduction is therefore verified against the authors\' own committed outputs, which is a stricter, file-level check. Comparing the paper\'s tables with Table 1 is listed as an open item (Section 9); the dashboard reads them from extension/web/paper_results.json once added. Any differences could stem from configuration differences between the paper\'s experiments and the repository\'s default main(); the repository is the authoritative artefact used here.'));

// 4 Design
c.push(h1('4. Design of the extension'));
c.push(...figure('figures/fig_architecture.png', 560, 'Figure 1. Architecture. Orange: this project. Blue: the original AutoScaleSim, used unchanged.'));
c.push(h2('4.1 Integration without changing the original algorithms'));
c.push(p('AutoScaleSim\'s Executor acts on the latest PlannerHistory record. The extension subclasses the original PlannerRuleBased as GuardedPlanner: it first calls super.doPlanning(), so the original rule makes its decision exactly as before, and only then reads that decision, explains it and, in guard mode, replaces it. The simulation set-up is the same as ExperimentalSetup.main; the original private factory methods are called by reflection so that entities are created identically. This gives three clearly labelled result types:'));
['**1. Original AutoScaleSim** — the authors\' main(), untouched.',
  '**2. Original, analysed by the extension** — the guard observes and explains but never changes decisions. Tests prove the outputs are byte-identical to type 1.',
  '**3. Budget-guarded policy** — a separate simulation in which the guard\'s decisions are executed.'].forEach((t) => c.push(bullet(t)));
c.push(h2('4.2 Budget guard'));
c.push(p('At every scaling decision (every 10 simulated minutes) the guard computes, using the simulator\'s own billing rule:'));
['**Committed cost** C: for destroyed VMs, their final bill; for live VMs, price × max(1, ceil(hours alive at the next decision)). Looking ahead to the next decision prevents hour roll-overs between checks.',
  '**Paced allowance** A = B × min(1, (t + 1 h)/T) − r, where B is the budget converted to US$, T the simulated period and r one billed hour of the minimum fleet, reserved so that the fleet can always be paid for.',
  '**Decision rule**: if C > A and the fleet is above its minimum, remove one VM (BUDGET_RECOVERY, or CONFLICT if the response-time target is also missed); if C > A at the minimum fleet, report INFEASIBLE; if the original rule wants to scale up and C + price > A, hold (BUDGET_HOLD or CONFLICT); otherwise keep the original decision (OK or PERF_MISS).'].forEach((t) => c.push(bullet(t)));
c.push(p('The guard never adds capacity that the original rule did not ask for, and with an unlimited budget it changes nothing (verified by test). Budgets are entered in Indian rupees and converted with a user-supplied exchange rate (default ₹85 per US$, an assumption that is shown on every result page).'));
c.push(h2('4.3 Explanations and warnings'));
c.push(p('Each decision is stored with the analysed delay, observed response time, fleet size, original and guarded decisions, committed cost, allowance and status, and an explanation assembled from those numbers. For example: "Analysed delay time 2.812 s was above the scale-up threshold (1.00 s), so the original SLA-aware rule decided SCALE_UP. Adding one VM would raise committed renting cost to ₹13.60 (one more billed hour costs ₹3.40), above the paced allowance of ₹12.47, so the guard keeps the current 3 VM(s). Observed response time 4.812 s misses the 4.00 s target. WARNING: budget and performance target conflict here." A run-level warning is raised when the guarded run is within budget but misses the target, or exceeds the budget because even the minimum fleet does not fit.'));
c.push(h2('4.4 Custom workloads, forecasting and spike warnings'));
['**Input**: per-minute counts (minute,requests) or a request log with one timestamp per row (epoch or ISO-8601), 120 minutes to 7 days, at most 2000 requests per minute; malformed lines are rejected with their line number.',
  '**Replay**: the workload is written in the exact layout of Dataset_NASA_July.tset (input = [weekend, day, hour, minute, count], output = sorted arrival second of each request), so the original EndUserEmulator replays it unchanged. Arrival seconds are drawn uniformly at random with a fixed seed, matching the measured trace (about 8.3 % of arrivals in every 5-second band).',
  '**Forecast**: 10-minute buckets (the scaling interval); Holt-Winters additive with damped trend (φ = 0.98) and a daily season of 144 buckets when at least two days exist, otherwise damped Holt; α, β, γ chosen by grid search on the series; compared with last-value and same-time-yesterday baselines.',
  '**Spikes**: a bucket above the trailing 2-hour mean + k·σ (k = 2.5) and at least 1.2 × that mean. **Warnings**: issued h buckets ahead when the forecast\'s 90 % upper bound (forecast + 1.645 × std of past h-step errors) crosses the threshold known at that time; backtested for precision and recall.',
  '**Capacity and cost**: one t2.medium completes 60 × 2500 MIPS × 2 PEs ÷ (5000 MI × 2 PEs) = 30 requests per minute; required VMs = ceil(rate ÷ (30 × 0.70)), where 70 % is the original CPU scale-up threshold; each hour is charged at its peak VM count × US$0.04.'].forEach((t) => c.push(bullet(t)));
c.push(h2('4.5 Web application, database and deployment'));
c.push(p('A Java web server (JDK HttpServer) serves the dashboard and a JSON API. Simulations are queued and run one at a time in child JVMs (queue limit 3), so the public deployment cannot be overloaded. The database stores runs (parameters, status, summary, result folder), per-variant metrics and uploaded workloads (metadata and normalised CSV). SQLite is the default because one server with one writer needs no database server; the same SQL also runs on PostgreSQL (tested against PostgreSQL 16) through DATABASE_URL, for example Amazon RDS. The application is packaged as a Docker image with a health check. A CloudFormation template creates an EC2 instance (Amazon Linux 2023, t3.small, encrypted disk, IMDSv2, HTTP only, Systems Manager access instead of SSH) that builds and runs the image; the stack completes only after the health endpoint answers.'));

// 5 Methodology
c.push(h1('5. Experimental methodology'));
c.push(p('All experiments use the default AutoScaleSim configuration unless stated, run on the same machine, and are regenerated by one command (java -jar dist/autoscalesim-lab.jar evaluate). AutoScaleSim is deterministic for this configuration (round-robin load balancing, oldest-VM removal), so each simulation is run once; the replay experiment uses three random seeds.'));
c.push(table([
  ['Metric', 'Unit', 'Definition', 'Source'],
  ['Response time', 's', 'Mean time from arrival to completion of a request', 'AutoScaleSim'],
  ['Delay time', 's', 'Response time minus processing time', 'AutoScaleSim'],
  ['SLA violation', '%', 'Share of requests whose delay exceeds the 1 s SLA', 'AutoScaleSim'],
  ['Renting cost', 'US$ → ₹', 'Σ ceil(VM hours) × price', 'AutoScaleSim (converted by extension)'],
  ['SLA penalty / total cost', 'US$ → ₹', 'Violated hours × price; renting + penalty', 'AutoScaleSim'],
  ['Max VMs, provisioned, contradictory decisions', 'count', 'Fleet and planner statistics', 'AutoScaleSim'],
  ['Budget compliance', 'yes/no', 'Renting cost ≤ budget', 'Extension'],
  ['Meets RT target', 'yes/no', 'Average response time ≤ target', 'Extension'],
  ['Decision status counts', 'count', 'OK, PERF_MISS, BUDGET_HOLD, CONFLICT, BUDGET_RECOVERY, INFEASIBLE', 'Extension'],
  ['Forecast MAE', 'req/min', 'Mean absolute error of h-step-ahead forecasts after warm-up', 'Extension'],
  ['Warning precision / recall', 'ratio', 'Warned buckets that were spikes / spikes that were warned', 'Extension'],
], [2300, 1000, 3826, 1900], { size: 17 }));
c.push(caption('Table 2. Metrics, units and where each comes from.'));
c.push(table([
  ['Experiment', 'Question', 'Set-up'],
  ['E1 Reproduction', 'Does the original code reproduce the authors\' outputs?', 'Authors\' main(); compare 3 monitoring files'],
  ['E2 Budget sweep', 'How do cost and performance trade off under a budget?', 'NASA 7 days; budgets ₹500–₹3000; target 4 s'],
  ['E3 Replay fidelity', 'How faithful is replaying per-minute counts?', 'NASA week 1: exact copy, random-uniform ×3 seeds, even spacing'],
  ['E4 Forecast backtest', 'Is the forecast better than baselines; do warnings precede spikes?', 'NASA weeks 2–4 (real) and a labelled synthetic flash crowd; leads 10–60 min'],
  ['E5 Capacity estimate', 'Do the estimates match what the simulator needs?', 'NASA weeks 1–4 replayed under the original policy'],
], [1900, 3700, 3426], { size: 17 }));
c.push(caption('Table 3. Experiments.'));

// 6 Results
c.push(h1('6. Results'));
c.push(h2('6.1 E2 — Budget-guarded scaling'));
c.push(table([['Run', 'Budget ₹', 'Renting ₹', 'Within', 'Total ₹', 'Avg RT s', 'SLA viol. %', 'Max VMs', 'CONFLICT', 'INFEASIBLE']]
  .concat(e2.slice(1).map((r) => [r[0], r[1], r[2], r[3], r[4], r[5], r[7], r[8], r[14], r[16]])),
  [900, 850, 950, 750, 1000, 850, 1000, 800, 950, 976], { numeric: true, size: 16 }));
c.push(caption('Table 4. Budget sweep on the NASA trace (7 days). "Within" = renting cost ≤ budget.'));
c.push(...figure('figures/fig_budget_sweep.png', 600, 'Figure 2. Renting cost and average response time against the budget.'));
['For every feasible budget (₹750 and above) the guarded renting cost stayed within the budget; at ₹500 even the minimum fleet costs more (₹578), and the guard reported 1007 INFEASIBLE decisions instead of silently overspending.',
  'The trade-off is steep: lowering the budget from ₹2536 (what the original spends) to ₹2000 raised the average response time from 3.99 s to 8.90 s and the SLA violation from 29.6 % to 60.4 %. Because AutoScaleSim also charges SLA penalties, total cost rises when the budget only limits renting.',
  'From ₹2750 upwards the guard never intervenes and the result is identical to the original.',
  'The CONFLICT counts show where the budget and the 4 s target cannot both be met, which is exactly the information the owner needs to decide whether to raise the budget or relax the target.'].forEach((t) => c.push(bullet(t)));
c.push(h2('6.2 E3 — Fidelity of replaying uploaded workloads'));
c.push(table([['Replay of NASA week 1', 'Avg RT (s)', 'SLA viol. (%)', 'Max VMs', 'Renting (₹)']].concat(e3.slice(1).map((r) => [r[0], r[1], r[2], r[3], r[4]])), [4000, 1300, 1300, 1000, 1426], { numeric: true, size: 17 }));
c.push(caption('Table 5. NASA week 1 replayed through the upload pipeline (original policy).'));
c.push(...figure('figures/fig_replay_fidelity.png', 520, 'Figure 3. Average response time of each replay variant.'));
c.push(p('Replaying an exact copy of the original rows gives identical results, so the pipeline itself is exact. A per-minute CSV loses the timing of requests inside each minute; drawing arrival seconds uniformly at random gives 4.9–5.2 s (three seeds) against 3.99 s, while evenly spacing them gives 14.7 s. Replays of uploaded workloads are therefore indicative, and the dashboard says so.'));
c.push(h2('6.3 E4 — Forecast accuracy and spike warnings'));
c.push(table([['Workload', 'Lead', 'MAE HW', 'MAE last', 'MAE season', 'Spikes', 'Warnings', 'Precision', 'Recall']]
  .concat(e4.slice(1).map((r) => [r[0], r[1] + ' min', r[3], r[4], r[5], r[6], r[7], r[8], r[9]])),
  [2300, 800, 850, 850, 950, 750, 900, 850, 776], { numeric: true, size: 16 }));
c.push(caption('Table 6. Backtest on 10-minute buckets, k = 2.5. HW = Holt-Winters; season = same time yesterday.'));
c.push(...figure('figures/fig_forecast_mae.png', 520, 'Figure 4. 30-minute-ahead forecast error.'));
c.push(p('Holt-Winters was far more accurate than the same-time-yesterday baseline on every workload, and slightly more accurate than the last-value baseline at 20–60-minute leads on NASA weeks 2 and 3; on week 4 and at the 10-minute lead the two were within about 0.3 requests/min, and last-value was sometimes better. Spike warnings caught 43–58 % of spikes on the real weeks with precision of 0.10–0.21, so many warnings are false alarms. A sudden synthetic flash crowd is largely unpredictable 30 minutes ahead (recall 0.22). The forecasting module is therefore useful for capacity planning and early attention, not as a reliable spike oracle.'));
c.push(h2('6.4 E5 — Capacity and cost estimates'));
c.push(table([['Workload', 'Estimated max VMs', 'Simulated max VMs', 'Estimated cost (₹)', 'Simulated renting (₹)', 'Simulated avg RT (s)']].concat(e5.slice(1).map((r) => [r[0], r[1], r[2], r[3], r[4], r[5]])), [1700, 1500, 1500, 1500, 1500, 1326], { numeric: true, size: 17 }));
c.push(caption('Table 7. Estimated vs simulated peak VMs and weekly renting cost (₹).'));
c.push(...figure('figures/fig_capacity_estimate.png', 600, 'Figure 5. Estimates from the forecast module against AutoScaleSim.'));
c.push(p('Estimated peak VMs were within ±2 of what the original policy used, and estimated weekly cost was 6–15 % below the simulated renting cost. The gap is expected: the estimate ignores the original policy\'s contradictory scale-up/scale-down actions, each of which is billed for a full hour.'));
c.push(h2('6.5 Dashboard'));
c.push(...figure('figures/shot_reproduction.png', 520, 'Figure 6. Reproduction page: the authors\' unmodified run and the byte-identity check.'));
c.push(...figure('figures/shot_budget.png', 520, 'Figure 7. Budget guard page: conflict warning, cost/response comparison, charts and explanations.'));
c.push(...figure('figures/shot_workload.png', 520, 'Figure 8. Workload page: uploaded flash-crowd workload, forecast, spikes, warnings and required VMs.'));

// 7 Testing
c.push(h1('7. Testing'));
c.push(table([
  ['Test class', 'Tests', 'What is checked'],
  ['BudgetGuardTest', '16', 'Budget, target and rate validation; billing identical to the original ceil rule; pacing; every branch of the decision rule; explanations built from recorded numbers; parsing of the original end-of-run report; CSV round trip; report generation; scenario defaults equal to the authors\' main()'],
  ['WorkloadTest', '13', 'Both CSV formats, ISO and epoch timestamps; rejection of empty, non-numeric, negative, too short, too long and too large inputs; trace-file layout and determinism; VM capacity from simulator constants; Holt-Winters beats naive on a daily pattern; detection of an injected spike; forecast horizon limit'],
  ['StoreAndWebTest', '9', 'SQLite and PostgreSQL 16 persistence (runs, metrics, workloads, interrupted runs); health/info API; upload, analysis and download; HTTP 400 for invalid parameters; HTTP 404 for path traversal; dashboard files served'],
  ['IntegrationTest', '8', 'Original run byte-identical to the authors\' outputs; observe mode does not change the simulation; cost estimate consistent with the final bill; unlimited budget changes nothing; tight budget enforced; exact trace copy replays identically; uploaded workload replayed completely; Wikipedia scenario runs'],
], [1700, 700, 6626], { size: 17 }));
c.push(caption('Table 8. Automated tests (JUnit 5). Result of the last run: 46 found, 46 passed, 0 failed, 0 skipped.'));
c.push(p('In addition, a browser test (Playwright) drove every dashboard feature end to end: reproduction, Wikipedia scenario, budget comparison, sweep, upload, analysis, replay with the guard, invalid upload and history, in light, dark and mobile layouts, with no JavaScript errors other than the expected HTTP 400 for the invalid upload. The Docker image was built and run: health check "healthy", reproduction byte-identical inside the container, data preserved across a container restart, and PostgreSQL mode working with the same image.'));

// 8 Cloud concepts
c.push(h1('8. Cloud computing concepts demonstrated'));
['**Elasticity and the MAPE loop**: AutoScaleSim monitors, analyses, plans and executes VM scaling every 10 minutes; the extension adds a budget check to the Plan phase.',
  '**Pay-as-you-go pricing**: hourly billing with rounding up explains why contradictory scaling decisions waste money and why the guard looks ahead to billing-hour boundaries.',
  '**SLAs**: response-time targets, SLA violations and penalties are measured and traded against cost.',
  '**Capacity planning**: forecasts of demand are turned into VM counts and cost.',
  '**IaaS deployment, containers and infrastructure as code**: the application runs in Docker on an AWS EC2 instance created by a CloudFormation template, with a health-gated rollout, persistent volume and least-privilege access.',
  'All scaling actions are **simulated**: the project does not provision real VMs for the Web application under study; only the dashboard itself runs on real cloud infrastructure.'].forEach((t) => c.push(bullet(t)));

// 9 Limitations
c.push(h1('9. Limitations and future work'));
['The paper\'s reported results have not yet been transcribed and compared (Section 3.3).',
  'Per-minute uploads lose intra-minute burstiness (E3), so replays are indicative.',
  'The guard paces the budget linearly; a demand-aware pacing that saves budget for predicted peaks could reduce CONFLICT decisions. The guard currently limits renting cost only, while total cost also includes SLA penalties.',
  'The forecast is a classical model with modest gains over a naive baseline; spike warnings have low precision. Model comparison on more traces and learning-based predictors are natural next steps.',
  'Costs use AutoScaleSim\'s US$ prices for t2.medium and a fixed exchange-rate assumption, not live AWS prices.',
  'The deployment is a single EC2 instance; a production design would add HTTPS, authentication and a managed database.'].forEach((t) => c.push(bullet(t)));

// 10 Conclusion
c.push(h1('10. Conclusion'));
c.push(p(`The AutoScaleSim implementation was reproduced from the authors' repository with only library and build-path fixes, and its outputs match the authors' own files byte for byte. On top of the unchanged simulator, a budget guard, decision explanations, conflict warnings, workload upload, forecasting and capacity estimation were added, compared fairly against the original on identical workloads, tested with 46 automated tests, and delivered as a database-backed dashboard packaged for AWS. The results are mixed in an informative way: budgets are respected, but tight budgets cost much more response time and SLA penalty; forecasts help planning but do not reliably predict sudden spikes. Making these trade-offs visible and explained is the main practical value of the extension.`));

// References
c.push(h1('References'));
[
  '[1] M. S. Aslanpour, A. N. Toosi, J. Taheri and R. Gaire, "AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds," Simulation Modelling Practice and Theory, vol. 108, Art. no. 102245, 2021, doi: 10.1016/j.simpat.2020.102245.',
  '[2] R. N. Calheiros, R. Ranjan, A. Beloglazov, C. A. F. De Rose and R. Buyya, "CloudSim: A toolkit for modeling and simulation of cloud computing environments and evaluation of resource provisioning algorithms," Software: Practice and Experience, vol. 41, no. 1, pp. 23–50, 2011.',
  '[3] T. Lorido-Botran, J. Miguel-Alonso and J. A. Lozano, "A review of auto-scaling techniques for elastic applications in cloud environments," Journal of Grid Computing, vol. 12, no. 4, pp. 559–592, 2014.',
  '[4] C. Qu, R. N. Calheiros and R. Buyya, "Auto-scaling Web applications in clouds: A taxonomy and survey," ACM Computing Surveys, vol. 51, no. 4, Art. no. 73, 2018.',
  '[5] J. O. Kephart and D. M. Chess, "The vision of autonomic computing," Computer, vol. 36, no. 1, pp. 41–50, 2003.',
  '[6] C. C. Holt, "Forecasting seasonals and trends by exponentially weighted moving averages," International Journal of Forecasting, vol. 20, no. 1, pp. 5–10, 2004 (reprint of a 1957 report).',
  '[7] P. R. Winters, "Forecasting sales by exponentially weighted moving averages," Management Science, vol. 6, no. 3, pp. 324–342, 1960.',
  '[8] E. S. Gardner Jr. and E. McKenzie, "Forecasting trends in time series," Management Science, vol. 31, no. 10, pp. 1237–1246, 1985.',
  '[9] M. F. Arlitt and C. L. Williamson, "Web server workload characterization: The search for invariants," ACM SIGMETRICS Performance Evaluation Review, vol. 24, no. 1, pp. 126–137, 1996.',
  '[10] M. S. Aslanpour et al., "AutoScaleSim," GitHub repository. [Online]. Available: https://github.com/aslanpour/AutoScaleSim',
  '[11] D. Gunning, M. Stefik, J. Choi, T. Miller, S. Stumpf and G.-Z. Yang, "XAI—Explainable artificial intelligence," Science Robotics, vol. 4, no. 37, Art. no. eaay7120, 2019.',
].forEach((t) => c.push(new Paragraph({ spacing: { after: 100 }, indent: { left: 540, hanging: 540 }, children: [new TextRun({ text: t, font: FONT, size: 20 })] })));

// Appendix
c.push(h1('Appendix A. How to run'));
['Windows: install JDK 17+ and run scripts\\start.bat, then open http://localhost:8080/.',
  'Docker: docker compose up -d --build.',
  'AWS: upload deploy/aws/autoscalesim-lab.yaml in CloudFormation with your public repository URL (docs/DEPLOYMENT.md).',
  'Tests: scripts\\test.ps1 all (Windows) or scripts/test.sh all.',
  'All experiments: java -jar dist/autoscalesim-lab.jar evaluate.'].forEach((t) => c.push(bullet(t)));

// ------------------------------------------------------------------ document
const doc = new Document({
  creator: 'AutoScaleSim Lab', title: 'Reproduction and Extension of AutoScaleSim',
  styles: {
    default: { document: { run: { font: FONT, size: 22 } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 30, bold: true, color: '1F3A5F', font: FONT }, paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 25, bold: true, color: '2A78D6', font: FONT }, paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 1 } },
    ]
  },
  numbering: { config: [
    { reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 270 } } } }] },
    { reference: 'numbers', levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] },
  ] },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 18, color: '7D7C77' })] })] }) },
    children: c
  }]
});
Packer.toBuffer(doc).then((b) => { fs.writeFileSync(path.join(ROOT, 'docs', 'AutoScaleSim_Lab_Report.docx'), b); console.log('report written'); });
