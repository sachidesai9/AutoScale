// Generates docs/AutoScaleSim_Lab_Presentation.pptx (16:9, speaker notes on every slide).
// Run: node docs/build_slides.js   (needs: npm install pptxgenjs)
const fs = require('fs');
const path = require('path');
const pptxgen = require('pptxgenjs');

const ROOT = path.join(__dirname, '..');
const csv = (f) => fs.readFileSync(path.join(ROOT, 'docs', 'results', f), 'utf8').trim().split('\n').map((l) => l.split(','));
const e1 = Object.fromEntries(csv('e1_reproduction.csv').slice(1).map((r) => [r[0], r[1]]));
const e2 = csv('e2_budget_sweep.csv');
const e4 = csv('e4_forecast_backtest.csv'); // model column has commas -> re-read safely below
const e4rows = fs.readFileSync(path.join(ROOT, 'docs', 'results', 'e4_forecast_backtest.csv'), 'utf8').trim().split('\n').slice(1)
  .map((l) => { const m = l.match(/^([^,]+),(\d+),(".*?"|[^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*),([^,]*)$/); return m ? m.slice(1) : null; }).filter(Boolean);

const NAVY = '1B2A4A', INK = '1F2328', INK2 = '57606A', BLUE = '2A78D6', ORANGE = 'EB6834', GREEN = '1BAF7A', GREY = '8A8984', LIGHT = 'F3F6FB', WHITE = 'FFFFFF';
const HEAD = 'Cambria', BODY = 'Calibri';

const pres = new pptxgen();
pres.layout = 'LAYOUT_WIDE'; // 13.33 x 7.5 in
pres.title = 'AutoScaleSim Lab';
pres.theme = { headFontFace: HEAD, bodyFontFace: BODY };

pres.defineSlideMaster({
  title: 'DARK', background: { color: NAVY },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 0.8, y: 2.2, w: 11.7, h: 1.6, fontFace: HEAD, fontSize: 40, bold: true, color: WHITE, valign: 'top', align: 'left', margin: 0 }, text: '' } }]
});
pres.defineSlideMaster({
  title: 'CONTENT', background: { color: WHITE },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 0.6, y: 0.35, w: 12.1, h: 0.85, fontFace: HEAD, fontSize: 30, bold: true, color: NAVY, valign: 'middle', align: 'left', margin: 0 }, text: '' } }],
  slideNumber: { x: 12.4, y: 7.0, w: 0.6, h: 0.3, fontFace: BODY, fontSize: 10, color: GREY }
});

const text = (s, t, o) => s.addText(t, { isTextBox: true, fontFace: BODY, fontSize: 16, color: INK, valign: 'top', margin: 0, ...o });
function card(s, x, y, w, h, fill) {
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.12, fill: { color: fill || LIGHT }, line: { color: fill || LIGHT } });
}
function circleNum(s, x, y, n, color) {
  s.addShape(pres.shapes.OVAL, { x, y, w: 0.55, h: 0.55, fill: { color }, line: { color } });
  text(s, String(n), { x, y, w: 0.55, h: 0.55, align: 'center', valign: 'middle', bold: true, color: WHITE, fontSize: 18 });
}
function stat(s, x, y, w, big, label, color) {
  text(s, big, { x, y, w, h: 0.95, fontFace: HEAD, fontSize: 44, bold: true, color: color || NAVY });
  text(s, label, { x, y: y + 0.95, w, h: 0.7, fontSize: 14, color: INK2 });
}
const content = (title, section) => { const s = pres.addSlide({ masterName: 'CONTENT', sectionTitle: section }); s.addText(title, { placeholder: 'title' }); return s; };
const chartText = { catAxisLabelColor: INK2, valAxisLabelColor: INK2, catAxisLabelFontFace: '+mn-lt', valAxisLabelFontFace: '+mn-lt', catAxisLabelFontSize: 12, valAxisLabelFontSize: 12,
  valGridLine: { color: 'E4E3DF', size: 1 }, catGridLine: { style: 'none' }, legendFontFace: '+mn-lt', legendFontSize: 12, legendColor: INK2, titleFontFace: '+mn-lt', titleColor: INK, titleFontSize: 14 };

// 1 Title
pres.addSection({ title: 'Introduction' });
let s = pres.addSlide({ masterName: 'DARK', sectionTitle: 'Introduction' });
s.addText('Reproduction and Extension of AutoScaleSim', { placeholder: 'title' });
text(s, 'Budget-aware, explainable and predictive cloud auto-scaling', { x: 0.8, y: 3.55, w: 11.7, h: 0.6, fontSize: 22, color: 'CADCFC' });
text(s, 'Base paper: Aslanpour, Toosi, Taheri & Gaire, "AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds", SIMPAT, vol. 108, 2021', { x: 0.8, y: 5.4, w: 11.7, h: 0.5, fontSize: 14, color: 'CADCFC' });
text(s, 'Cloud Computing project · [Your name] · [Roll number]', { x: 0.8, y: 5.95, w: 11.7, h: 0.5, fontSize: 14, color: WHITE });
s.addNotes('Good morning. My project reproduces a published cloud simulator, AutoScaleSim, from the authors\' own Java code, and then extends it with three features: a budget guard that explains its scaling decisions, a workload upload with traffic forecasting, and a deployed web dashboard. I will show what is original, what I added, how I tested it, and the honest results, including where the extensions do not help.');

// 2 Problem
s = content('The problem: auto-scalers ignore budgets and never explain', 'Introduction');
[['Rule-based auto-scalers react to CPU or latency only', 'They add VMs whenever latency is high, whatever it costs. Owners with a fixed allowance cannot say "stay within ₹2000 this week".'],
 ['Decisions are opaque', 'A log line says SCALE_UP, but not why, or what it cost, or what was traded away.'],
 ['Traffic spikes arrive before capacity does', 'A new VM takes minutes to start; reacting only after a spike is too late.']].forEach(([h, b], i) => {
  circleNum(s, 0.7, 1.6 + i * 1.65, i + 1, i === 2 ? GREEN : ORANGE);
  text(s, h, { x: 1.5, y: 1.55 + i * 1.65, w: 10.8, h: 0.5, fontSize: 20, bold: true, color: NAVY });
  text(s, b, { x: 1.5, y: 2.05 + i * 1.65, w: 10.8, h: 0.8, fontSize: 16, color: INK2 });
});
s.addNotes('Cloud auto-scaling follows the monitor-analyse-plan-execute loop. Common policies are thresholds on CPU or delay. Three gaps motivate this project: they are blind to budget, they do not explain themselves, and they only react after load has increased. Testing ideas on a real cloud is expensive and not repeatable, so I use a simulator.');

// 3 AutoScaleSim
s = content('Base: AutoScaleSim, a CloudSim toolkit for Web-app auto-scaling', 'Introduction');
const phases = [['Monitor', 'CPU, delay, response time, SLA, every minute'], ['Analyse', 'Simple, moving averages, exponential smoothing'], ['Plan', '7 rules: resource-, SLA-aware, hybrid, alarms'], ['Execute', 'Add or remove VMs; 5-min start-up; oldest-VM removal']];
phases.forEach(([h, b], i) => {
  const x = 0.6 + i * 3.1;
  card(s, x, 1.6, 2.8, 2.2, i === 2 ? 'FDEEE6' : LIGHT);
  text(s, h, { x: x + 0.25, y: 1.8, w: 2.3, h: 0.5, fontSize: 20, bold: true, color: i === 2 ? ORANGE : BLUE });
  text(s, b, { x: x + 0.25, y: 2.35, w: 2.3, h: 1.3, fontSize: 15, color: INK2 });
  if (i < 3) s.addShape(pres.shapes.RIGHT_TRIANGLE, { x: x + 2.85, y: 2.55, w: 0.2, h: 0.3, rotate: 90, fill: { color: GREY }, line: { color: GREY } });
});
text(s, [{ text: 'Default experiment: ', options: { bold: true } }, { text: 'NASA Web trace (July 1995), 7 days, 2 initial VMs, scale every 10 min, SLA-aware rule (up if delay > 1 s, down if < 0.2 s), t2.medium at US$0.04/h, 1–40 VMs.' }],
  { x: 0.6, y: 4.3, w: 12.1, h: 0.9, fontSize: 16 });
text(s, [{ text: 'Cost model: ', options: { bold: true } }, { text: 'every VM billed ceil(hours alive) × price; SLA penalty per violated hour. The extension reuses this exact rule.' }],
  { x: 0.6, y: 5.25, w: 12.1, h: 0.9, fontSize: 16 });
s.addNotes('AutoScaleSim extends CloudSim with the pieces needed to host and auto-scale a web application, and it implements each MAPE phase separately. I read the source code to identify the policies: five analysers, seven planning rules, two executors and several VM-removal policies. The orange box, Plan, is where my budget guard plugs in. The cost model bills every VM per started hour, which turns out to matter.');

// 4 Original vs ours
s = content('Original work stays separate from my additions', 'Introduction');
card(s, 0.6, 1.5, 5.9, 4.9, 'EAF2FC');
text(s, 'Original (Aslanpour et al.)', { x: 0.9, y: 1.7, w: 5.3, h: 0.5, fontSize: 20, bold: true, color: BLUE });
text(s, [
  { text: 'Simulator, MAPE components, rules, executors', options: { bullet: true, breakLine: true } },
  { text: 'Cost and SLA metrics, end-of-run report', options: { bullet: true, breakLine: true } },
  { text: 'NASA and Wikipedia traces', options: { bullet: true, breakLine: true } },
  { text: 'Used unchanged in src/ (git diff is empty)', options: { bullet: true } }], { x: 0.9, y: 2.35, w: 5.3, h: 3.6, fontSize: 16, paraSpaceAfter: 10 });
card(s, 6.8, 1.5, 5.9, 4.9, 'FDEEE6');
text(s, 'Added in this project', { x: 7.1, y: 1.7, w: 5.3, h: 0.5, fontSize: 20, bold: true, color: ORANGE });
text(s, [
  { text: '1. Reproduction runner + byte-identity check', options: { bullet: true, breakLine: true } },
  { text: '2. Budget guard, explanations, conflict warnings', options: { bullet: true, breakLine: true } },
  { text: '3. Workload upload, forecast, spike warnings, capacity & cost', options: { bullet: true, breakLine: true } },
  { text: 'Dashboard, API, SQLite/PostgreSQL, Docker, AWS', options: { bullet: true, breakLine: true } },
  { text: '54 JUnit tests, evaluation command', options: { bullet: true } }], { x: 7.1, y: 2.35, w: 5.3, h: 3.6, fontSize: 16, paraSpaceAfter: 10 });
text(s, 'Not claimed as novel research: cost-aware scaling, explainability and forecasting all exist in the literature. New relative to the AutoScaleSim code base.', { x: 0.6, y: 6.55, w: 12.1, h: 0.5, fontSize: 13, color: INK2, italic: true });
s.addNotes('This separation was a hard rule. The authors\' sources are untouched, and every result is labelled: original, original analysed by my extension, or budget-guarded. I am also careful not to claim novelty: budget-aware scaling and forecasting are studied in the literature; what is new is integrating them with this simulator.');

// 5 Reproduction
pres.addSection({ title: 'Reproduction' });
s = content('Reproduction: outputs identical to the authors\' own files', 'Reproduction');
stat(s, 0.6, 1.5, 3.8, '3 / 3', 'monitoring files byte-identical to the authors\' committed CSVs', GREEN);
stat(s, 4.7, 1.5, 2.6, Number(e1.total_requests).toLocaleString('en-IN'), 'requests replayed');
stat(s, 7.5, 1.5, 2.6, e1.response_time_avg_s + ' s', 'average response time');
stat(s, 10.3, 1.5, 2.6, 'US$' + e1.renting_cost_usd, 'renting cost (7 days)');
card(s, 0.6, 3.9, 12.1, 2.25);
text(s, 'What it took', { x: 0.9, y: 4.05, w: 5, h: 0.45, fontSize: 18, bold: true, color: NAVY });
text(s, [
  { text: 'Missing JARs (authors\' D:\\Dropbox paths): same versions from Maven Central; Neuroph 2.92 repackage', options: { bullet: true, breakLine: true } },
  { text: 'Compiled unchanged with JDK 21 using --release 8, as the NetBeans project specifies', options: { bullet: true, breakLine: true } },
  { text: 'Each simulation in its own JVM and folder: the code keeps static state and writes src/others/', options: { bullet: true, breakLine: true } },
  { text: 'Paper PDF was not reachable from the build environment: paper-table comparison still to do', options: { bullet: true } }], { x: 0.9, y: 4.55, w: 11.5, h: 1.5, fontSize: 15, paraSpaceAfter: 6 });
s.addNotes('I ran the authors\' main method without changing a line. It writes three per-minute monitoring files of ten thousand rows each, and all three are byte-identical to the files the authors committed to GitHub. That is a stricter check than comparing a few averages. One honest gap: I could not download the paper PDF in my build environment, so comparing against the paper\'s tables is still open.');

// 6 Budget guard design
pres.addSection({ title: 'Budget guard' });
s = content('Budget guard: the original rule decides, then the guard checks', 'Budget guard');
const steps = [['Original rule', 'PlannerRuleBased decides SCALE_UP / DOWN / NOTHING, unchanged'], ['Committed cost', 'Simulator billing: ceil(hours) × price, looking 10 min ahead'],
  ['Paced budget', 'Budget × elapsed share, minus one hour for the minimum fleet'], ['Decide + explain', 'Hold, remove one VM, or keep; record status and reason']];
steps.forEach(([h, b], i) => {
  const x = 0.6 + i * 3.1;
  circleNum(s, x, 1.55, i + 1, i === 0 ? BLUE : ORANGE);
  text(s, h, { x, y: 2.25, w: 2.8, h: 0.5, fontSize: 19, bold: true, color: NAVY });
  text(s, b, { x, y: 2.75, w: 2.8, h: 1.2, fontSize: 15, color: INK2 });
});
const statuses = [['OK', GREEN], ['PERF_MISS', 'B07A00'], ['BUDGET_HOLD', INK2], ['BUDGET_RECOVERY', INK2], ['CONFLICT', 'C62828'], ['INFEASIBLE', 'C62828']];
statuses.forEach(([t, col], i) => {
  const x = 0.6 + (i % 3) * 4.05, y = 4.45 + Math.floor(i / 3) * 0.7;
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w: 3.8, h: 0.5, rectRadius: 0.25, fill: { color: WHITE }, line: { color: col, width: 1.5 } });
  text(s, t, { x, y, w: 3.8, h: 0.5, align: 'center', valign: 'middle', fontSize: 14, bold: true, color: col });
});
text(s, 'Three labelled results: 1 original · 2 original analysed (proven identical) · 3 budget-guarded (decisions executed)', { x: 0.6, y: 6.0, w: 12.1, h: 0.5, fontSize: 15, color: INK2 });
s.addNotes('The guard is a subclass of the original planner. It calls super first, so the original rule decides exactly as before. Then it computes committed cost with the simulator\'s own billing rule, compares it with a paced budget, and either keeps the decision, holds a scale-up, or removes one VM. Every decision gets one of six statuses. CONFLICT means the response-time target is missed and the budget prevents adding capacity: that is the warning the owner needs.');

// 7 Sweep chart
s = content('Budgets are respected, but tight budgets cost response time', 'Budget guard');
const g = e2.slice(2);
const labels = g.map((r) => '₹' + r[1]);
s.addChart(pres.charts.LINE, [
  { name: 'Budget ₹', labels, values: g.map((r) => +r[1]) },
  { name: 'Original renting ₹', labels, values: g.map(() => +e2[1][2]) },
  { name: 'Budget-guarded renting ₹', labels, values: g.map((r) => +r[2]) }],
  { x: 0.5, y: 1.4, w: 6.2, h: 4.4, chartColors: [GREY, BLUE, ORANGE], lineDash: ['dash', 'solid', 'solid'], lineSize: 2, lineDataSymbolSize: 6, showLegend: true, legendPos: 'b',
    showTitle: true, title: 'Renting cost vs budget (₹)', valAxisMinVal: 0, ...chartText });
s.addChart(pres.charts.LINE, [
  { name: 'Target 4 s', labels, values: g.map(() => 4) },
  { name: 'Original avg RT (s)', labels, values: g.map(() => +e2[1][5]) },
  { name: 'Budget-guarded avg RT (s)', labels, values: g.map((r) => +r[5]) }],
  { x: 6.8, y: 1.4, w: 6.0, h: 4.4, chartColors: [GREY, BLUE, ORANGE], lineDash: ['dash', 'solid', 'solid'], lineSize: 2, lineDataSymbolSize: 6, showLegend: true, legendPos: 'b',
    showTitle: true, title: 'Average response time vs budget (s)', valAxisMinVal: 0, ...chartText });
const r2000 = e2.find((r) => r[1] === '2000');
text(s, `Within budget for every feasible budget (₹750+); ₹500 flagged INFEASIBLE. At ₹2000: ₹${r2000[2]} renting, ${r2000[5]} s (original ${e2[1][5]} s), SLA penalties raise total cost.`,
  { x: 0.6, y: 6.0, w: 12.1, h: 0.8, fontSize: 15, color: INK2 });
s.addNotes('Each point is a full seven-day simulation. On the left, the guarded renting cost follows the budget line exactly and never exceeds a feasible budget; at 500 rupees even one VM costs 578, and the guard reports infeasible instead of silently overspending. On the right is the price: response time rises steeply as the budget shrinks, and because AutoScaleSim charges SLA penalties, total cost actually goes up. This trade-off is exactly what the conflict warnings make visible.');

// 8 Explanation example
s = content('Every decision is explained from recorded numbers', 'Budget guard');
card(s, 0.6, 1.5, 12.1, 2.6, 'FDECEC');
text(s, 'SATURDAY 00:20 · 3 VMs · original SCALE_UP → guard DO_NOTHING · CONFLICT', { x: 0.9, y: 1.65, w: 11.5, h: 0.45, fontSize: 16, bold: true, color: 'C62828' });
text(s, '"Analysed delay time 2.812 s was above the scale-up threshold (1.00 s), so the original SLA-aware rule decided SCALE_UP. Adding one VM would raise committed renting cost to ₹13.60 (one more billed hour costs ₹3.40), above the paced allowance of ₹12.47, so the guard keeps the current 3 VM(s). Observed response time 4.812 s misses the 4.00 s target. WARNING: budget and performance target conflict here."',
  { x: 0.9, y: 2.15, w: 11.5, h: 1.85, fontSize: 16, italic: true, color: INK });
[['1008', 'decisions explained per 7-day run'], ['6', 'status types, filterable in the dashboard'], ['CSV + HTML', 'downloadable decision log and report']].forEach(([b, l], i) => stat(s, 0.6 + i * 4.1, 4.5, 3.8, b, l, i === 0 ? ORANGE : NAVY));
s.addNotes('This is a real explanation from the two-thousand-rupee run, generated from the numbers recorded at that decision, not a template sentence. It names the trigger, the original decision, the cost arithmetic and the target, and flags the conflict. Every one of the 1008 decisions in a week has one; the dashboard filters them by status and they download as CSV.');

// 9 Workload feature
pres.addSection({ title: 'Workloads and forecasting' });
s = content('Upload a workload, forecast it, warn before spikes', 'Workloads and forecasting');
const wsteps = [['Upload CSV', 'Per-minute counts or a request log; validated, stored in the database'], ['Replay', 'Converted to the exact NASA trace format; unmodified emulator replays it'],
  ['Forecast', 'Holt-Winters with daily season and damped trend; grid-searched'], ['Warn + size', 'Spike = 2-h mean + 2.5σ; warning 30 min ahead; VMs and ₹ cost']];
wsteps.forEach(([h, b], i) => {
  card(s, 0.6, 1.45 + i * 1.3, 4.6, 1.15, i === 2 ? 'E5F6EF' : LIGHT);
  text(s, h, { x: 0.85, y: 1.55 + i * 1.3, w: 4.2, h: 0.4, fontSize: 17, bold: true, color: i === 2 ? GREEN : NAVY });
  text(s, b, { x: 0.85, y: 1.95 + i * 1.3, w: 4.2, h: 0.6, fontSize: 14, color: INK2 });
});
s.addImage({ path: path.join(ROOT, 'docs', 'figures', 'shot_workload.png'), x: 5.5, y: 1.45, w: 7.2, h: 7.2 * 1560 / 1360 > 5.3 ? 5.3 : 7.2 * 1560 / 1360, sizing: { type: 'contain', w: 7.2, h: 5.3 } });
s.addNotes('The third feature lets a user upload their own traffic. The file is converted into the exact format of the NASA trace, so the original emulator replays it without modification. The forecast is Holt-Winters exponential smoothing with a daily season, chosen because it is fast and explainable. Spikes are buckets well above the recent average, and a warning fires when the forecast upper bound crosses that threshold thirty minutes ahead. Demand is converted into VMs and rupees with the simulator\'s own capacity and price.');

// 10 Forecast results
s = content('Forecasting helps planning, but spikes stay hard to predict', 'Workloads and forecasting');
const f30 = e4rows.filter((r) => r[1] === '30');
const flabels = f30.map((r) => r[0].replace(' (real)', '').replace(' (synthetic)', ' (synth.)'));
s.addChart(pres.charts.BAR, [
  { name: 'Holt-Winters', labels: flabels, values: f30.map((r) => +r[3]) },
  { name: 'Last value', labels: flabels, values: f30.map((r) => +r[4]) },
  { name: 'Same time yesterday', labels: flabels, values: f30.map((r) => +r[5]) }],
  { x: 0.5, y: 1.4, w: 7.4, h: 4.6, barDir: 'col', chartColors: [GREEN, BLUE, GREY], barGapWidthPct: 60, showLegend: true, legendPos: 'b',
    showTitle: true, title: '30-min-ahead MAE (requests/min, lower is better)', valAxisMinVal: 0, ...chartText });
stat(s, 8.3, 1.5, 4.4, '0.37–0.58', 'recall: share of real spikes warned 10–60 min ahead (NASA weeks 2–4)', GREEN);
stat(s, 8.3, 3.25, 4.4, '0.11–0.21', 'precision: most warnings are false alarms', 'C62828');
stat(s, 8.3, 5.0, 4.4, '±2 VMs', 'estimated vs simulated peak fleet (cost 6–15 % low)', NAVY);
s.addNotes('Forecast accuracy: Holt-Winters is far better than same-time-yesterday, and better than last-value at thirty and sixty minutes on all three real weeks. The fit is burst-robust: a one-off spike of up to an hour is kept out of the trend and the daily season, so it is not predicted again tomorrow; an earlier version without this flagged every future bucket as a spike after a single burst. Spike warnings catch roughly 40 to 60 percent of real spikes with many false alarms, and a sudden synthetic flash crowd is mostly unpredictable thirty minutes ahead. Capacity estimates land within two VMs of what the simulator used. So: useful for planning, not a reliable spike oracle.');

// 11 Architecture & deployment
pres.addSection({ title: 'System' });
s = content('One container: dashboard, API, database and simulator', 'System');
s.addImage({ path: path.join(ROOT, 'docs', 'figures', 'fig_architecture.png'), x: 0.5, y: 1.4, w: 7.4, h: 7.4 * 750 / 1440, sizing: { type: 'contain', w: 7.4, h: 3.9 } });
text(s, [
  { text: 'Java web server + JSON API, single-worker job queue', options: { bullet: true, breakLine: true } },
  { text: 'Database: SQLite on a volume; PostgreSQL via DATABASE_URL (tested on 16)', options: { bullet: true, breakLine: true } },
  { text: 'Docker image with health check', options: { bullet: true, breakLine: true } },
  { text: 'AWS CloudFormation: EC2 t3.small, HTTP only, Session Manager, encrypted disk', options: { bullet: true, breakLine: true } },
  { text: 'Stack succeeds only after /api/health answers', options: { bullet: true } }], { x: 8.2, y: 1.5, w: 4.6, h: 4.6, fontSize: 15, paraSpaceAfter: 10 });
s.addNotes('Everything ships as one Docker container. The dashboard calls a Java API, which queues simulations and runs each one in its own JVM. Runs, metrics and uploaded workloads are stored in SQLite by default; the same code runs on PostgreSQL, which I tested. On AWS, a CloudFormation template creates one EC2 instance that builds and runs the container, and the stack only reports success after the health endpoint answers, so a green stack means a working site.');

// 12 Testing
s = content('Verified: tests, browser walk-through, container', 'System');
stat(s, 0.6, 1.5, 3.8, '54 / 54', 'JUnit 5 tests passed (0 skipped)', GREEN);
stat(s, 4.7, 1.5, 3.8, '8', 'integration tests that run real simulations', NAVY);
stat(s, 8.8, 1.5, 3.9, '0', 'JavaScript errors in the browser walk-through', NAVY);
card(s, 0.6, 3.7, 12.1, 2.15);
text(s, [
  { text: 'Unit: validation, billing rule, pacing, every decision branch, explanations, CSV parsing, forecasting, spike detection', options: { bullet: true, breakLine: true } },
  { text: 'Database & API: SQLite and PostgreSQL, uploads, HTTP 400 on bad input, 404 on path traversal', options: { bullet: true, breakLine: true } },
  { text: 'Integration: authors\' outputs reproduced; observing changes nothing; unlimited budget changes nothing; budgets enforced', options: { bullet: true, breakLine: true } },
  { text: 'Container: healthy, reproduction exact inside Docker, data kept across restarts', options: { bullet: true } }], { x: 0.9, y: 3.9, w: 11.5, h: 1.85, fontSize: 15, paraSpaceAfter: 8 });
s.addNotes('Testing covers four levels. Unit tests check every branch of the decision rule and that billing matches the original ceil rule. Database and API tests run against both SQLite and a real PostgreSQL server. Integration tests run real simulations and prove the key claims: the original outputs are reproduced, observing changes nothing, and budgets are enforced. A browser test clicked through every feature with no JavaScript errors, and the Docker image was verified healthy.');

// 13 Limitations
pres.addSection({ title: 'Conclusion' });
s = content('Limitations and next steps', 'Conclusion');
[['Paper comparison pending', 'Transcribe the paper\'s tables once the PDF is available'], ['Replay fidelity', 'Per-minute CSVs lose intra-minute bursts (5.1 s vs 3.99 s)'],
 ['Linear budget pacing', 'Save budget for forecast peaks to cut CONFLICT decisions'], ['Forecast precision', 'Compare learning-based predictors on more traces'],
 ['Simulated scaling', 'Real cloud runs only the dashboard, not the scaled Web app'], ['Single instance', 'Add HTTPS, login and a managed database for production']].forEach(([h, b], i) => {
  const x = 0.6 + (i % 2) * 6.15, y = 1.45 + Math.floor(i / 2) * 1.75;
  card(s, x, y, 5.95, 1.5);
  text(s, h, { x: x + 0.3, y: y + 0.18, w: 5.4, h: 0.45, fontSize: 18, bold: true, color: NAVY });
  text(s, b, { x: x + 0.3, y: y + 0.68, w: 5.4, h: 0.7, fontSize: 15, color: INK2 });
});
s.addNotes('These are the honest limits. The paper-table comparison is pending. Replaying per-minute uploads is indicative, not exact. Budget pacing is linear, and a natural next step is to use the forecast to save budget for predicted peaks. And the scaling itself is simulated: the real cloud hosts the dashboard, not the web application being studied.');

// 14 Conclusion
s = pres.addSlide({ masterName: 'DARK', sectionTitle: 'Conclusion' });
s.addText('Reproduced exactly, extended separately, trade-offs made visible', { placeholder: 'title' });
text(s, [
  { text: 'Original AutoScaleSim outputs reproduced byte for byte', options: { bullet: true, breakLine: true } },
  { text: 'Budgets enforced and every decision explained, with conflict warnings', options: { bullet: true, breakLine: true } },
  { text: 'Custom workloads forecast, sized and replayed in the simulator', options: { bullet: true, breakLine: true } },
  { text: 'Live demo: dashboard → reproduction → budget guard → workload upload', options: { bullet: true } }], { x: 0.8, y: 3.9, w: 11.7, h: 2.6, fontSize: 18, color: WHITE, paraSpaceAfter: 10 });
s.addNotes('To conclude: the original simulator was reproduced exactly; the extensions sit beside it without changing it; and the results show real trade-offs rather than only improvements. I will now show the live dashboard: run the reproduction, run the budget guard at two thousand rupees and read one explanation, and upload the flash-crowd workload to show the forecast and spike warning.');

pres.writeFile({ fileName: path.join(ROOT, 'docs', 'AutoScaleSim_Lab_Presentation.pptx') }).then(() => console.log('deck written'));
