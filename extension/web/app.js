/* AutoScaleSim Lab dashboard. Every number shown is read from the API (real simulations, documented
   calculations, or the database). Nothing here invents data. */
(function () {
  'use strict';

  // ------------------------------------------------------------------ helpers
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const num = (v, d = 2) => (v == null || isNaN(v)) ? '–' : Number(v).toLocaleString('en-IN', { minimumFractionDigits: d, maximumFractionDigits: d });
  const int = (v) => (v == null || isNaN(v)) ? '–' : Math.round(v).toLocaleString('en-IN');
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const state = { info: null, charts: {}, rate: 85, workloads: [] };

  async function api(path, opts) {
    const r = await fetch(path, opts);
    let body = null;
    try { body = await r.json(); } catch (e) { /* non-JSON */ }
    if (!r.ok) throw new Error((body && body.error) || ('HTTP ' + r.status));
    return body;
  }

  const VARIANT = {
    original: '1. Original AutoScaleSim',
    observe: '2. Original, analysed by extension',
    guard: '3. Budget-guarded policy',
    original_policy: 'Original policy on uploaded workload'
  };
  const METRICS = [
    ['renting_cost_usd', 'Renting cost (₹)', 'inr'], ['sla_penalty_usd', 'SLA penalty (₹)', 'inr'], ['total_cost_usd', 'Total cost (₹)', 'inr'],
    ['response_time_avg_s', 'Avg response time (s)', 3], ['delay_time_avg_s', 'Avg delay time (s)', 3], ['sla_violation_pct', 'SLA violation (%)', 2],
    ['cpu_util_avg_pct', 'Avg CPU utilisation (%)', 2], ['max_vms', 'Max VMs used', 0], ['vms_provisioned', 'VMs provisioned', 0],
    ['contradictory_decisions', 'Contradictory decisions', 0], ['cloudlets_failed', 'Failed requests', 0], ['total_requests', 'Total requests', 0]
  ];
  const STATUS_MEANING = {
    OK: 'within paced budget, target met', PERF_MISS: 'within budget, target missed', BUDGET_HOLD: 'scale-up blocked by budget, target met',
    CONFLICT: 'target missed and budget blocks capacity', BUDGET_RECOVERY: 'spending ahead of budget, one VM removed', INFEASIBLE: 'over budget even at minimum fleet'
  };
  const fmtMetric = (v, kind, rate) => kind === 'inr' ? num(v * rate, 2) : (kind === 0 ? int(v) : num(v, kind));

  function tag(text, cls) { return `<span class="tag ${cls}">${esc(text)}</span>`; }
  function yesNo(b, yes = 'yes', no = 'no') { return b ? tag('✓ ' + yes, 'good') : tag('✕ ' + no, 'bad'); }
  function notice(kind, html) { return `<div class="notice ${kind}" role="${kind === 'bad' ? 'alert' : 'status'}">${html}</div>`; }

  // ------------------------------------------------------------------ tabs & theme
  function showPage(page) {
    $$('nav.tabs button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.page === page)));
    $$('section.page').forEach((s) => s.classList.toggle('active', s.id === 'page-' + page));
    if (page === 'history') loadHistory();
    try { localStorage.setItem('page', page); } catch (e) { /* storage unavailable */ }
  }
  $$('nav.tabs button').forEach((b) => b.addEventListener('click', () => showPage(b.dataset.page)));
  $('#themeToggle').addEventListener('click', () => {
    const cur = document.documentElement.getAttribute('data-theme');
    const dark = cur ? cur === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.setAttribute('data-theme', dark ? 'light' : 'dark');
    try { localStorage.setItem('theme', dark ? 'light' : 'dark'); } catch (e) { /* ignore */ }
    rerenderCharts();
  });
  try { const t = localStorage.getItem('theme'); if (t) document.documentElement.setAttribute('data-theme', t); } catch (e) { /* ignore */ }
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', rerenderCharts);

  // ------------------------------------------------------------------ charts
  const chartBuilders = {};
  function lineChart(id, canvasParent, cfg) {
    chartBuilders[id] = () => buildLineChart(id, canvasParent, cfg);
    chartBuilders[id]();
  }
  function rerenderCharts() { Object.keys(chartBuilders).forEach((k) => { if (document.body.contains($('#' + k))) chartBuilders[k](); }); }

  function buildLineChart(id, parent, cfg) {
    if (state.charts[id]) state.charts[id].destroy();
    let canvas = $('#' + id);
    if (!canvas) { canvas = document.createElement('canvas'); canvas.id = id; parent.appendChild(canvas); }
    const ink2 = css('--ink-2'), line = css('--line'), muted = css('--muted');
    const datasets = cfg.series.map((s) => ({
      label: s.label, data: s.data, borderColor: css(s.color), backgroundColor: s.fill ? css(s.color) + '22' : css(s.color),
      borderWidth: s.width || 2, pointRadius: s.points ? 4 : 0, pointHoverRadius: s.points ? 6 : 3, borderDash: s.dash ? [6, 4] : [],
      fill: s.fill || false, stepped: s.stepped || false, showLine: s.points ? false : true, spanGaps: true, tension: 0,
      pointStyle: s.pointStyle || 'circle', order: s.order || 0
    }));
    state.charts[id] = new Chart(canvas, {
      type: 'line',
      data: { labels: cfg.labels, datasets },
      options: {
        responsive: true, maintainAspectRatio: false, animation: false, normalized: true,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: { callbacks: { title: (items) => (cfg.xTitle || '') + ' ' + items[0].label,
            label: (c) => c.raw == null ? null : ` ${c.dataset.label}: ${typeof c.raw === 'number' ? num(c.raw, cfg.digits == null ? 2 : cfg.digits) : c.raw}` } }
        },
        scales: {
          x: { ticks: { color: muted, maxTicksLimit: 10, autoSkip: true, maxRotation: 0 }, grid: { display: false }, title: { display: !!cfg.xTitle, text: cfg.xTitle, color: ink2 } },
          y: { beginAtZero: true, ticks: { color: muted, maxTicksLimit: 6 }, grid: { color: line }, title: { display: !!cfg.yTitle, text: cfg.yTitle, color: ink2 } }
        }
      }
    });
  }
  function legend(items) {
    return '<div class="legend">' + items.map(([label, color, style]) =>
      style === 'dot' ? `<span><span class="dot" style="background:var(${color})"></span>${esc(label)}</span>`
        : `<span><span class="sw ${style === 'dash' ? 'dash' : ''}" style="background:var(${color})"></span>${esc(label)}</span>`).join('') + '</div>';
  }

  // ------------------------------------------------------------------ jobs
  async function runJob(params, statusEl, onDone) {
    const btns = $$('button.btn');
    statusEl.innerHTML = `<pre class="log"><span class="spinner"></span> Submitting…</pre>`;
    let sub;
    try {
      sub = await api('/api/runs', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) });
    } catch (e) { statusEl.innerHTML = notice('bad', esc(e.message)); return; }
    btns.forEach((b) => (b.disabled = true));
    const t0 = Date.now();
    const poll = async () => {
      let run;
      try { run = await api('/api/runs/' + sub.id); } catch (e) { statusEl.innerHTML = notice('bad', esc(e.message)); btns.forEach((b) => (b.disabled = false)); return; }
      const secs = Math.round((Date.now() - t0) / 1000);
      if (run.status === 'done' || run.status === 'failed') {
        btns.forEach((b) => (b.disabled = false));
        statusEl.innerHTML = run.status === 'done'
          ? `<p class="hint">Run <code>${esc(run.id)}</code> finished in ${secs} s and was saved to the database.</p>`
          : notice('bad', 'Run failed: ' + esc(run.error)) + `<pre class="log">${esc(run.log)}</pre>`;
        if (run.status === 'done') onDone(run);
        refreshInfo();
        return;
      }
      statusEl.innerHTML = `<pre class="log"><span class="spinner"></span> ${esc(run.status)} · ${secs} s\n${esc(run.log || '')}</pre>`;
      setTimeout(poll, 1200);
    };
    poll();
  }
  const parse = (s) => { try { return typeof s === 'string' ? JSON.parse(s) : (s || {}); } catch (e) { return {}; } };

  // ------------------------------------------------------------------ overview
  async function refreshInfo() {
    try {
      const i = await api('/api/info');
      state.info = i;
      state.rate = i.default_inr_per_usd;
      $('#paperRef').innerHTML = `Base paper: ${esc(i.paper)} <a href="${esc(i.doi)}" target="_blank" rel="noopener">${esc(i.doi)}</a> · Original code: <a href="${esc(i.repository)}" target="_blank" rel="noopener">${esc(i.repository)}</a>`;
      const repro = i.latest_reproduction ? parse(i.latest_reproduction.summary) : null;
      const ident = repro && repro.identical_to_authors_committed_outputs ? Object.values(repro.identical_to_authors_committed_outputs).every(Boolean) : null;
      $('#overviewKpis').innerHTML = [
        ['Database', esc(i.database), 'persists runs & workloads'],
        ['Simulation runs', int(i.runs), 'stored in the database'],
        ['Workloads', int(i.workloads), 'uploaded or sample'],
        ['Reproduction check', ident == null ? 'not run yet' : (ident ? 'identical ✓' : 'differs ✕'), 'vs authors\' committed outputs'],
        ['VM price', 'US$' + num(i.price_usd_per_hour_t2medium, 2) + '/h', 't2.medium, from AutoScaleSimTags']
      ].map(([l, v, d]) => `<div class="kpi"><div class="label">${l}</div><div class="value">${v}</div><div class="delta">${d}</div></div>`).join('');
    } catch (e) { $('#overviewKpis').innerHTML = notice('bad', 'Cannot reach the server: ' + esc(e.message)); }
  }

  // ------------------------------------------------------------------ reproduction
  $('#reproForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const scenario = new FormData(e.target).get('scenario');
    runJob({ kind: 'reproduce', scenario }, $('#reproStatus'), (run) => renderReproduce(run, $('#reproResult')));
  });

  function metricsTable(metricsByVariant, variants, rate) {
    let h = '<div class="scroll"><table><tr><th>Metric (from AutoScaleSim\'s own end-of-run report)</th>' + variants.map((v) => `<th class="n">${esc(VARIANT[v] || v)}</th>`).join('') + '</tr>';
    METRICS.forEach(([k, label, kind]) => {
      h += `<tr><td>${label}</td>` + variants.map((v) => `<td class="n">${fmtMetric((metricsByVariant[v] || {})[k], kind, rate)}</td>`).join('') + '</tr>';
    });
    return h + '</table></div>';
  }

  function renderReproduce(run, el) {
    const s = parse(run.summary);
    const variants = Object.keys(run.metrics);
    let h = '<div class="card">';
    h += `<div class="split"><h3>${esc(s.scenario || 'Reproduction')}</h3><span class="hint">Run ${esc(run.id)} · ${esc(run.created_at)}</span></div>`;
    if (s.identical_to_authors_committed_outputs) {
      const c = s.identical_to_authors_committed_outputs;
      const all = Object.values(c).every(Boolean);
      h += notice(all ? 'good' : 'bad', all ? '<b>Reproduced exactly.</b> All monitoring files produced by this run are byte-identical (ignoring line endings) to the files committed by the authors.'
        : '<b>Outputs differ</b> from the authors\' committed files.');
      h += '<div class="badge-row" style="margin-bottom:12px">' + Object.entries(c).map(([f, ok]) => `${yesNo(ok, f, f)}`).join('') + '</div>';
    } else {
      h += notice('info', 'The authors\' <code>main()</code> is hard-coded to NASA, so this scenario runs through the extension\'s runner with the values the authors documented in comments. There are no committed outputs to compare against.');
    }
    h += metricsTable(run.metrics, variants, state.rate);
    h += `<p class="hint">Costs are in ₹ at ₹${num(state.rate, 2)}/US$ (assumption); the simulator reports US$. <a href="/files/${esc(run.id)}/${esc(variants[0])}/console.log" target="_blank">Console output</a></p></div>`;
    el.innerHTML = h;
  }

  async function renderPaperCompare() {
    const el = $('#paperCompare');
    try {
      const p = await (await fetch('paper_results.json')).json();
      if (!p.values || !p.values.length) { el.innerHTML = notice('warn', esc(p.note)); return; }
      let h = `<p class="hint">${esc(p.source)}</p><div class="scroll"><table><tr><th>Metric</th><th class="n">Paper</th><th class="n">This reproduction</th><th>Note</th></tr>`;
      p.values.forEach((v) => { h += `<tr><td>${esc(v.metric)}</td><td class="n">${esc(v.paper)}</td><td class="n">${esc(v.reproduced)}</td><td>${esc(v.note || '')}</td></tr>`; });
      el.innerHTML = h + '</table></div>';
    } catch (e) { el.innerHTML = notice('warn', 'Paper comparison file not available.'); }
  }

  // ------------------------------------------------------------------ budget guard
  $('#budgetForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    state.rate = Number(f.inrPerUsd);
    runJob({ kind: 'compare', ...f }, $('#budgetStatus'), (run) => renderCompare(run, $('#budgetResult')));
  });

  async function renderCompare(run, el) {
    const s = parse(run.summary), p = parse(run.params);
    const rate = p.inrPerUsd || state.rate;
    const m = run.metrics, o = m.original || m.original_policy, g = m.guard;
    let h = '';
    if (s.conflict) h += notice('bad', `<b>⚠ Budget and performance target conflict.</b> Under ₹${num(s.budget_inr, 0)} the guarded run's average response time is ${num(g.response_time_avg_s, 2)} s, above the ${num(s.target_rt_s, 2)} s target. Both cannot be satisfied with this budget.`);
    if (!s.guard_within_budget) h += notice('bad', `<b>⚠ Budget exceeded.</b> Even the guarded run spent ₹${num(s.guard_renting_inr, 2)}: the budget is below the cost of the minimum fleet (see INFEASIBLE decisions).`);
    if (s.guard_within_budget && s.guard_meets_rt_target) h += notice('good', '<b>Both constraints met</b> by the budget-guarded run.');
    h += '<div class="kpis">' + [
      ['Renting cost · original', '₹' + num(s.original_renting_inr, 2), s.original_within_budget ? 'within budget' : 'over budget'],
      ['Renting cost · guarded', '₹' + num(s.guard_renting_inr, 2), s.guard_within_budget ? 'within budget' : 'over budget'],
      ['Avg response · original', num(o.response_time_avg_s, 2) + ' s', s.original_meets_rt_target ? 'meets target' : 'misses target'],
      ['Avg response · guarded', num(g.response_time_avg_s, 2) + ' s', s.guard_meets_rt_target ? 'meets target' : 'misses target'],
      ['SLA violation · guarded', num(g.sla_violation_pct, 1) + ' %', 'original ' + num(o.sla_violation_pct, 1) + ' %']
    ].map(([l, v, d]) => `<div class="kpi"><div class="label">${l}</div><div class="value">${v}</div><div class="delta">${d}</div></div>`).join('') + '</div>';
    const variants = ['original', 'observe', 'guard', 'original_policy'].filter((v) => m[v]);
    h += `<div class="card"><div class="split"><h3>Results (₹ at ₹${num(rate, 2)}/US$)</h3><span class="hint">`
      + (run.kind === 'compare' ? `<a href="/files/${esc(run.id)}/report.html" target="_blank">Download full HTML report</a> · ` : '')
      + `<a href="/files/${esc(run.id)}/guard/guard_decisions.csv">Decision log (CSV)</a></span></div>`
      + metricsTable(m, variants, rate)
      + (m.observe ? `<p class="hint">Columns 1 and 2 are the same simulation; the extension only observes in column 2. Identical: ${s.observe_identical_to_original ? '✓ yes' : '✕ no'}.</p>` : '') + '</div>';
    h += `<div class="grid two"><div class="card"><h3>Committed renting cost vs paced budget</h3><div id="lg-cost-${run.id}"></div><div class="chart-box" id="box-cost-${run.id}"></div></div>`
      + `<div class="card"><h3>VMs in service (hourly mean)</h3><div id="lg-vms-${run.id}"></div><div class="chart-box" id="box-vms-${run.id}"></div></div></div>`
      + `<div class="card"><h3>Response time (hourly mean)</h3><div id="lg-rt-${run.id}"></div><div class="chart-box" id="box-rt-${run.id}"></div></div>`;
    h += `<div class="card"><div class="split"><h3>Scaling-decision explanations</h3>
      <form class="controls" id="decForm-${run.id}">
        <label class="field">Run<select name="variant"><option value="guard">Budget-guarded (decisions executed)</option>${m.observe ? '<option value="observe">Original, analysed (decisions unchanged)</option>' : ''}</select></label>
        <label class="field">Status<select name="status"><option value="">All except OK</option><option value="*">All</option>${Object.keys(STATUS_MEANING).map((k) => `<option>${k}</option>`).join('')}</select></label>
      </form></div><div id="statusCounts-${run.id}"></div><div class="scroll scroll-y" id="dec-${run.id}"><div class="empty">Loading…</div></div></div>`;
    el.innerHTML = h;

    const ser = await api(`/api/runs/${run.id}/series`);
    const base = ser.observe || ser.original_policy || ser.original, gs = ser.guard;
    const baseName = ser.observe ? 'Original' : 'Original policy';
    const hours = (base.decisionHours || []).map((x) => num(x, 1));
    $('#lg-cost-' + run.id).innerHTML = legend([[baseName, '--s1'], ['Budget-guarded', '--s2'], ['Paced budget', '--ref', 'dash']]);
    lineChart('c-cost-' + run.id, $('#box-cost-' + run.id), { labels: hours, xTitle: 'Hour', yTitle: '₹ committed', series: [
      { label: baseName, data: base.committedInr, color: '--s1' }, { label: 'Budget-guarded', data: gs.committedInr, color: '--s2' },
      { label: 'Paced budget', data: base.allowanceInr, color: '--ref', dash: true }] });
    const hrs = (arr) => (arr || []).map((_, i) => i + 1);
    const baseVms = (ser.original || base).hourlyVms, baseRt = (ser.original || base).hourlyRt;
    $('#lg-vms-' + run.id).innerHTML = legend([[baseName, '--s1'], ['Budget-guarded', '--s2']]);
    lineChart('c-vms-' + run.id, $('#box-vms-' + run.id), { labels: hrs(baseVms), xTitle: 'Hour', yTitle: 'VMs', digits: 2, series: [
      { label: baseName, data: baseVms, color: '--s1' }, { label: 'Budget-guarded', data: gs.hourlyVms, color: '--s2' }] });
    $('#lg-rt-' + run.id).innerHTML = legend([[baseName, '--s1'], ['Budget-guarded', '--s2'], ['Target', '--ref', 'dash']]);
    lineChart('c-rt-' + run.id, $('#box-rt-' + run.id), { labels: hrs(baseRt), xTitle: 'Hour', yTitle: 'Seconds', series: [
      { label: baseName, data: baseRt, color: '--s1' }, { label: 'Budget-guarded', data: gs.hourlyRt, color: '--s2' },
      { label: 'Target', data: baseRt.map(() => p.targetRt), color: '--ref', dash: true }] });

    const form = $('#decForm-' + run.id);
    const load = async () => {
      const f = Object.fromEntries(new FormData(form));
      const box = $('#dec-' + run.id);
      const d = await api(`/api/runs/${run.id}/decisions?variant=${encodeURIComponent(f.variant)}`);
      const counts = {};
      d.rows.forEach((r) => (counts[r[13]] = (counts[r[13]] || 0) + 1));
      $('#statusCounts-' + run.id).innerHTML = '<div class="badge-row" style="margin:4px 0 10px">' + Object.keys(STATUS_MEANING).map((k) =>
        `<span class="tag ${k}" title="${esc(STATUS_MEANING[k])}">${k} · ${counts[k] || 0}</span>`).join('') + '</div>';
      const rows = d.rows.map((r, i) => [r, i]).filter(([r]) => f.status === '*' || (f.status === '' ? r[13] !== 'OK' : r[13] === f.status));
      if (!rows.length) { box.innerHTML = '<div class="empty">No decisions with this status.</div>'; return; }
      box.innerHTML = '<table><tr><th>Time</th><th class="n">Delay / RT (s)</th><th class="n">VMs</th><th>Original</th><th>Guard</th><th class="n">Committed / allowance (₹)</th><th>Status</th><th>Explanation</th></tr>'
        + rows.slice(0, 400).map(([r, i]) => {
          const next = d.rows[i + 1] ? d.rows[i + 1][5] : '';
          return `<tr><td>${esc(r[1])}</td><td class="n">${num(+r[3], 2)} / ${num(+r[4], 2)}</td><td class="n">${esc(r[5])}${next !== '' ? ' → ' + esc(next) : ''}</td><td>${esc(r[6])}</td><td>${esc(r[7])}</td><td class="n">${num(+r[9], 2)} / ${num(+r[10], 2)}</td><td><span class="tag ${esc(r[13])}">${esc(r[13])}</span></td><td>${esc(r[14])}</td></tr>`;
        }).join('') + '</table>' + (rows.length > 400 ? `<p class="hint">Showing 400 of ${rows.length}; download the CSV for all.</p>` : '');
    };
    form.addEventListener('change', load);
    load();
  }

  // ------------------------------------------------------------------ sweep
  $('#sweepForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    runJob({ kind: 'sweep', budgets: f.budgets, targetRt: f.targetRt, inrPerUsd: state.rate }, $('#sweepStatus'), (run) => renderSweep(run, $('#sweepResult')));
  });

  function renderSweep(run, el) {
    const s = parse(run.summary), p = parse(run.params);
    const rows = s.rows || [];
    if (rows.length < 2) { el.innerHTML = notice('warn', 'No sweep rows.'); return; }
    const hdr = rows[0], body = rows.slice(1), idx = (k) => hdr.indexOf(k);
    let h = '<div class="scroll"><table><tr>' + ['Run', 'Budget ₹', 'Renting ₹', 'Within budget', 'Total cost ₹', 'Avg RT (s)', 'Meets RT target', 'SLA viol. %', 'Max VMs'].map((x) => `<th>${x}</th>`).join('') + '</tr>';
    body.forEach((r) => {
      h += `<tr><td>${esc(r[0])}</td><td class="n">${esc(r[idx('budget_inr')])}</td><td class="n">${esc(r[idx('renting_cost_inr')])}</td><td>${r[idx('within_budget')] === '-' ? '–' : yesNo(r[idx('within_budget')] === 'true')}</td>`
        + `<td class="n">${esc(r[idx('total_cost_inr')])}</td><td class="n">${esc(r[idx('avg_response_time_s')])}</td><td>${yesNo(r[idx('meets_rt_target')] === 'true')}</td><td class="n">${esc(r[idx('sla_violation_pct')])}</td><td class="n">${esc(r[idx('max_vms')])}</td></tr>`;
    });
    h += '</table></div>';
    const g = body.filter((r) => r[0] === 'guard');
    const o = body.find((r) => r[0] === 'original');
    h += `<div class="grid two" style="margin-top:12px"><div><h3>Renting cost vs budget</h3>${legend([['Budget-guarded', '--s2'], ['Original (no budget)', '--s1', 'dash'], ['Budget line', '--ref', 'dash']])}<div class="chart-box" id="box-sw1-${run.id}"></div></div>`
      + `<div><h3>Average response time vs budget</h3>${legend([['Budget-guarded', '--s2'], ['Original (no budget)', '--s1', 'dash'], ['Target', '--ref', 'dash']])}<div class="chart-box" id="box-sw2-${run.id}"></div></div></div>`
      + '<p class="hint">Two separate charts (cost and time have different units). Every point is a full 7-day simulation.</p>';
    el.innerHTML = h;
    const labels = g.map((r) => '₹' + r[idx('budget_inr')]);
    lineChart('c-sw1-' + run.id, $('#box-sw1-' + run.id), { labels, xTitle: 'Budget', yTitle: '₹ renting cost', series: [
      { label: 'Budget-guarded', data: g.map((r) => +r[idx('renting_cost_inr')]), color: '--s2', points: false },
      { label: 'Original', data: g.map(() => +o[idx('renting_cost_inr')]), color: '--s1', dash: true },
      { label: 'Budget', data: g.map((r) => +r[idx('budget_inr')]), color: '--ref', dash: true }] });
    lineChart('c-sw2-' + run.id, $('#box-sw2-' + run.id), { labels, xTitle: 'Budget', yTitle: 'Seconds', series: [
      { label: 'Budget-guarded', data: g.map((r) => +r[idx('avg_response_time_s')]), color: '--s2' },
      { label: 'Original', data: g.map(() => +o[idx('avg_response_time_s')]), color: '--s1', dash: true },
      { label: 'Target', data: g.map(() => p.targetRt), color: '--ref', dash: true }] });
  }

  // ------------------------------------------------------------------ workloads
  async function loadWorkloads(selectId) {
    state.workloads = await api('/api/workloads');
    const sel = $('#workloadSelect');
    sel.innerHTML = state.workloads.map((w) => `<option value="${esc(w.id)}">${esc(w.name)} · ${int(w.minutes)} min</option>`).join('');
    if (selectId) sel.value = selectId;
  }

  $('#uploadForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = $('#uploadFile').files[0];
    const st = $('#uploadStatus');
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) { st.innerHTML = notice('bad', 'File is larger than 20 MB.'); return; }
    st.innerHTML = '<p class="hint"><span class="spinner"></span> Uploading and validating…</p>';
    try {
      const text = await file.text();
      const w = await api('/api/workloads?name=' + encodeURIComponent(file.name), { method: 'POST', headers: { 'Content-Type': 'text/csv' }, body: text });
      st.innerHTML = notice('good', `Stored <b>${esc(w.name)}</b> (${esc(w.format)}, ${int(w.minutes)} minutes, ${int(w.total_requests)} requests). ${esc(w.notes || '')}`);
      await loadWorkloads(w.id);
      analyse();
    } catch (err) { st.innerHTML = notice('bad', 'Upload rejected: ' + esc(err.message)); }
  });
  $('#analyseForm').addEventListener('submit', (e) => { e.preventDefault(); analyse(); });

  async function analyse() {
    const f = Object.fromEntries(new FormData($('#analyseForm')));
    const el = $('#analysisResult');
    if (!f.workloadId) { el.innerHTML = notice('warn', 'Upload or choose a workload first.'); return; }
    el.innerHTML = '<div class="card"><span class="spinner"></span> Forecasting…</div>';
    let a;
    try { a = await api(`/api/workloads/${f.workloadId}/analysis?k=${f.k}&lead=${f.lead}&util=${f.util}&inrPerUsd=${state.rate}`); }
    catch (err) { el.innerHTML = notice('bad', esc(err.message)); return; }
    const s = a.summary, bm = a.bucketMinutes;
    const beatsNaive = s.mae_model < s.mae_naive_last_value;
    const futureSpikes = s.future_spike_buckets;
    let h = '';
    if (futureSpikes > 0) {
      const first = a.futureSpike.indexOf(1);
      h += notice('bad', `<b>⚠ Predicted traffic spike</b> in about ${num((first + 1) * bm / 60, 1)} h: the forecast exceeds the 95th percentile of observed demand (${num(s.future_spike_threshold_req_per_min, 1)} req/min) in ${futureSpikes} of the next ${a.futureRate.length} buckets. Plan up to <b>${s.future_max_required_vms} VMs</b>.`);
    } else {
      h += notice('good', `No spike predicted in the next ${num(s.future_hours, 0)} h (forecast stays below the 95th percentile of observed demand, ${num(s.future_spike_threshold_req_per_min, 1)} req/min). Plan up to ${s.future_max_required_vms} VMs.`);
    }
    if (s.note) h += notice('info', esc(s.note));
    a.notes.forEach((n) => (h += notice('info', esc(n))));
    h += '<div class="kpis">' + [
      ['Requests', int(s.total_requests), `${int(s.minutes)} minutes · peak ${int(s.peak_req_per_min)}/min`],
      ['Forecast error (MAE)', num(s.mae_model, 2), `req/min · naive ${num(s.mae_naive_last_value, 2)}${s.mae_seasonal_naive != null ? ' · seasonal ' + num(s.mae_seasonal_naive, 2) : ''}`],
      ['Spikes in history', int(s.spikes_detected), `warnings ${int(s.warnings_issued)}`],
      ['Warning precision / recall', (s.warning_precision == null ? '–' : num(s.warning_precision, 2)) + ' / ' + (s.warning_recall == null ? '–' : num(s.warning_recall, 2)), `${s.forecast_lead_minutes} min ahead (backtest)`],
      ['Next ' + num(s.future_hours, 0) + ' h', s.future_max_required_vms + ' VMs', 'est. ₹' + num(s.estimated_cost_future_inr, 2)]
    ].map(([l, v, d]) => `<div class="kpi"><div class="label">${l}</div><div class="value">${v}</div><div class="delta">${esc(d)}</div></div>`).join('') + '</div>';
    h += `<div class="card"><h3>History: demand, ${s.forecast_lead_minutes}-minute-ahead forecast and spikes</h3>`
      + legend([['Observed', '--s1'], ['Forecast', '--s3'], ['Upper bound (90%)', '--s3', 'dash'], ['Spike threshold', '--ref', 'dash'], ['Spike', '--bad', 'dot'], ['Warning issued', '--s4', 'dot']])
      + '<div class="chart-box tall" id="box-hist"></div>'
      + `<p class="hint">${esc(s.model)}. Forecast beats the last-value baseline: ${beatsNaive ? '✓ yes' : '✕ no'}. Rates are mean requests per minute in ${bm}-minute buckets.</p></div>`;
    h += `<div class="grid two"><div class="card"><h3>Forecast: next ${num(s.future_hours, 0)} hours</h3>`
      + legend([['Forecast', '--s3'], ['Upper bound', '--s3', 'dash'], ['Spike threshold (p95)', '--ref', 'dash']])
      + '<div class="chart-box" id="box-fut"></div></div>'
      + `<div class="card"><h3>Required VMs (t2.medium at ${num(s.target_utilisation * 100, 0)}% CPU)</h3>`
      + legend([['History (observed demand)', '--s1'], ['Future (upper bound)', '--s3']])
      + `<div class="chart-box" id="box-vms"></div><p class="hint">One VM completes ${num(s.vm_capacity_req_per_min, 1)} requests/min at 100% CPU (VM MIPS × PEs ÷ request length × PEs). Cost estimate: each hour charged at its peak VM count × US$0.04.</p></div></div>`;
    h += `<div class="card"><h3>Replay this workload in AutoScaleSim</h3><form class="controls" id="wlRunForm">
        <label class="field"><span><input type="checkbox" name="guard" value="true"> also run the budget guard</span></label>
        <label class="field">Budget (₹)<input type="number" name="budgetInr" value="2000" min="1" step="any"></label>
        <label class="field">RT target (s)<input type="number" name="targetRt" value="4" min="0.01" step="any"></label>
        <button class="btn" type="submit">Run simulation</button></form>
        <p class="hint">Per-minute counts are replayed by the original emulator with arrival seconds drawn uniformly at random (fixed seed). Validation on NASA week 1: average response time 5.10 s vs 3.99 s with the original intra-minute arrivals, so treat replays as indicative.</p>
        <div id="wlRunStatus"></div><div id="wlRunResult"></div></div>`;
    el.innerHTML = h;

    const n = a.rate.length;
    const labels = a.rate.map((_, i) => num(i * bm / 60, 1));
    const sp = a.rate.map((v, i) => (a.spike[i] ? v : null));
    const wr = a.rate.map((v, i) => (a.warned[i] ? (a.forecast[i] != null ? a.forecast[i] : v) : null));
    lineChart('c-hist', $('#box-hist'), { labels, xTitle: 'Hour', yTitle: 'Requests / min', series: [
      { label: 'Observed', data: a.rate, color: '--s1', order: 3 }, { label: 'Forecast', data: a.forecast, color: '--s3', order: 2 },
      { label: 'Upper bound', data: a.upper, color: '--s3', dash: true, width: 1, order: 2 }, { label: 'Spike threshold', data: a.threshold, color: '--ref', dash: true, width: 1, order: 4 },
      { label: 'Spike', data: sp, color: '--bad', points: true, order: 0 }, { label: 'Warning', data: wr, color: '--s4', points: true, pointStyle: 'triangle', order: 1 }] });
    const flabels = a.futureRate.map((_, i) => '+' + num((i + 1) * bm / 60, 1));
    lineChart('c-fut', $('#box-fut'), { labels: flabels, xTitle: 'Hours ahead', yTitle: 'Requests / min', series: [
      { label: 'Forecast', data: a.futureRate, color: '--s3' }, { label: 'Upper bound', data: a.futureUpper, color: '--s3', dash: true, width: 1 },
      { label: 'Spike threshold', data: a.futureRate.map(() => a.futureThreshold), color: '--ref', dash: true, width: 1 }] });
    const vl = labels.concat(flabels);
    lineChart('c-vms', $('#box-vms'), { labels: vl, xTitle: 'Hour', yTitle: 'VMs', digits: 0, series: [
      { label: 'History', data: a.requiredVms.concat(a.futureVms.map(() => null)), color: '--s1', stepped: true },
      { label: 'Future', data: Array(n).fill(null).concat(a.futureVms), color: '--s3', stepped: true }] });

    $('#wlRunForm').addEventListener('submit', (e) => {
      e.preventDefault();
      const ff = Object.fromEntries(new FormData(e.target));
      runJob({ kind: 'workload', workloadId: f.workloadId, guard: ff.guard || 'false', budgetInr: ff.budgetInr, targetRt: ff.targetRt, inrPerUsd: state.rate },
        $('#wlRunStatus'), (run) => renderWorkloadRun(run, $('#wlRunResult')));
    });
  }

  function renderWorkloadRun(run, el) {
    const s = parse(run.summary), p = parse(run.params);
    if (run.metrics.guard) { renderCompare(run, el); return; }
    el.innerHTML = '<div class="kpis">' + [
      ['Estimated max VMs', int(s.estimated_max_vms), 'from forecast module'],
      ['Simulated max VMs', int(s.simulated_max_vms), 'AutoScaleSim, original policy'],
      ['Estimated cost', '₹' + num(s.estimated_cost_inr, 2), 'peak-hour estimate'],
      ['Simulated renting cost', '₹' + num(s.simulated_renting_cost_inr, 2), 'AutoScaleSim billing']
    ].map(([l, v, d]) => `<div class="kpi"><div class="label">${l}</div><div class="value">${v}</div><div class="delta">${d}</div></div>`).join('') + '</div>'
      + metricsTable(run.metrics, Object.keys(run.metrics), p.inrPerUsd || state.rate);
  }

  // ------------------------------------------------------------------ history
  async function loadHistory() {
    try {
      const runs = await api('/api/runs?limit=100');
      if (!runs.length) $('#runsTable').innerHTML = '<div class="empty">No runs yet.</div>';
      else {
        $('#runsTable').innerHTML = '<table><tr><th>Run</th><th>Kind</th><th>Status</th><th>Created (UTC)</th><th>Parameters</th><th class="n">Renting ₹ (guard / original)</th></tr>'
          + runs.map((r) => {
            const p = parse(r.params), m = r.metrics || {};
            const o = m.original || m.original_policy || m.observe, g = m.guard;
            const rate = p.inrPerUsd || state.rate;
            const params = r.kind === 'compare' ? `₹${p.budgetInr} · ${p.targetRt} s` : r.kind === 'reproduce' ? p.scenario : r.kind === 'sweep' ? (p.budgets || []).join(', ') : (p.guard ? 'with guard' : 'original policy');
            const st = r.status === 'done' ? 'good' : r.status === 'failed' ? 'bad' : 'warn';
            return `<tr class="clickable" data-id="${esc(r.id)}"><td><code>${esc(r.id)}</code></td><td>${esc(r.kind)}</td><td>${tag(r.status, st)}</td><td>${esc((r.created_at || '').replace('T', ' ').slice(0, 19))}</td><td>${esc(params)}</td>`
              + `<td class="n">${g ? num(g.renting_cost_usd * rate, 2) + ' / ' : ''}${o ? num(o.renting_cost_usd * rate, 2) : '–'}</td></tr>`;
          }).join('') + '</table>';
        $$('#runsTable tr.clickable').forEach((tr) => tr.addEventListener('click', () => openRun(tr.dataset.id)));
      }
      const ws = await api('/api/workloads');
      $('#workloadsTable').innerHTML = ws.length ? '<table><tr><th>Name</th><th>Format</th><th>Uploaded (UTC)</th><th class="n">Minutes</th><th class="n">Requests</th><th class="n">Peak / min</th><th></th></tr>'
        + ws.map((w) => `<tr><td>${esc(w.name)}</td><td>${esc(w.format)}</td><td>${esc((w.uploaded_at || '').replace('T', ' ').slice(0, 19))}</td><td class="n">${int(w.minutes)}</td><td class="n">${int(w.total_requests)}</td><td class="n">${int(w.peak_per_min)}</td>`
          + `<td><a href="#" data-wl="${esc(w.id)}">Analyse</a> · <a href="/api/workloads/${esc(w.id)}/csv">CSV</a></td></tr>`).join('') + '</table>' : '<div class="empty">No workloads.</div>';
      $$('#workloadsTable a[data-wl]').forEach((a) => a.addEventListener('click', async (e) => {
        e.preventDefault(); showPage('workload'); await loadWorkloads(a.dataset.wl); analyse();
      }));
    } catch (e) { $('#runsTable').innerHTML = notice('bad', esc(e.message)); }
  }

  async function openRun(id) {
    const el = $('#historyRun');
    const run = await api('/api/runs/' + id);
    if (run.status !== 'done') { el.innerHTML = notice(run.status === 'failed' ? 'bad' : 'info', `Run ${esc(id)}: ${esc(run.status)} ${esc(run.error || '')}`); return; }
    el.innerHTML = `<h3 style="margin-top:8px">Run ${esc(id)} (${esc(run.kind)})</h3><div id="historyBody"></div>`;
    const body = $('#historyBody');
    if (run.kind === 'compare') renderCompare(run, body);
    else if (run.kind === 'reproduce') renderReproduce(run, body);
    else if (run.kind === 'sweep') { body.innerHTML = '<div class="card" id="hsw"></div>'; renderSweep(run, $('#hsw')); }
    else renderWorkloadRun(run, body);
    el.scrollIntoView({ behavior: 'smooth' });
  }

  // ------------------------------------------------------------------ start
  refreshInfo();
  renderPaperCompare();
  loadWorkloads().then(() => { if (state.workloads.length) analyse(); }).catch(() => {});
  try { const pg = localStorage.getItem('page'); if (pg && $('#page-' + pg)) showPage(pg); } catch (e) { /* ignore */ }
})();
