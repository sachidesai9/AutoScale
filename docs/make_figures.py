# Regenerates docs/figures/*.png from docs/results/*.csv. Needs: pip install matplotlib
# Run from the repository root: python docs/make_figures.py
import csv, sys
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch
import os
BASE = os.path.dirname(os.path.abspath(__file__)); R = BASE + "/results/"; O = BASE + "/figures/"
S1, S2, S3, REF, INK, INK2, LINE = "#2a78d6", "#eb6834", "#1baf7a", "#8a8984", "#0b0b0b", "#52514e", "#e4e3df"
plt.rcParams.update({"font.size": 10, "axes.edgecolor": LINE, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True, "grid.color": LINE, "grid.linewidth": .8,
                     "axes.axisbelow": True, "legend.frameon": False, "savefig.dpi": 200, "savefig.bbox": "tight"})
rd = lambda f: list(csv.DictReader(open(R + f)))

# F2 budget sweep
rows = rd("e2_budget_sweep.csv"); orig = rows[0]; g = rows[1:]
b = [float(r["budget_inr"]) for r in g]
fig, ax = plt.subplots(1, 2, figsize=(10, 3.6))
ax[0].plot(b, [float(r["renting_cost_inr"]) for r in g], color=S2, lw=2, marker="o", ms=4, label="Budget-guarded")
ax[0].axhline(float(orig["renting_cost_inr"]), color=S1, lw=2, ls="--", label="Original (no budget)")
ax[0].plot(b, b, color=REF, lw=1.2, ls=":", label="Budget line")
ax[0].set_xlabel("Budget for 7 days (₹)"); ax[0].set_ylabel("Renting cost (₹)"); ax[0].set_title("Renting cost vs budget", loc="left", color=INK)
ax[0].legend(fontsize=8.5)
ax[1].plot(b, [float(r["avg_response_time_s"]) for r in g], color=S2, lw=2, marker="o", ms=4, label="Budget-guarded")
ax[1].axhline(float(orig["avg_response_time_s"]), color=S1, lw=2, ls="--", label="Original (no budget)")
ax[1].axhline(4.0, color=REF, lw=1.2, ls=":", label="Target 4 s")
ax[1].set_xlabel("Budget for 7 days (₹)"); ax[1].set_ylabel("Average response time (s)"); ax[1].set_title("Response time vs budget", loc="left", color=INK)
ax[1].legend(fontsize=8.5); ax[0].set_ylim(0); ax[1].set_ylim(0)
fig.savefig(O + "fig_budget_sweep.png"); plt.close(fig)

# F3 replay fidelity
rows = rd("e3_replay_fidelity.csv")
lab = ["Authors' run", "Exact copy", "Uniform\nseed A", "Uniform\nseed 1", "Uniform\nseed 2", "Even\nspacing"]
v = [float(r["response_time_avg_s"]) for r in rows]
cols = [S1, S1, S3, S3, S3, REF]
fig, ax = plt.subplots(figsize=(7.5, 3.2))
bars = ax.bar(lab, v, color=cols, width=.6)
for bb, x in zip(bars, v): ax.text(bb.get_x() + bb.get_width()/2, x + .3, f"{x:.2f}", ha="center", fontsize=8.5, color=INK2)
ax.set_ylabel("Average response time (s)"); ax.set_title("Replaying NASA week 1 from per-minute counts", loc="left", color=INK); ax.grid(axis="x", visible=False)
fig.savefig(O + "fig_replay_fidelity.png"); plt.close(fig)

# F4 forecast MAE at 30 min
rows = [r for r in rd("e4_forecast_backtest.csv") if r["lead_min"] == "30"]
import numpy as np
x = np.arange(len(rows)); w = .26
fig, ax = plt.subplots(figsize=(8, 3.3))
ax.bar(x - w, [float(r["mae_model"]) for r in rows], w, color=S3, label="Holt-Winters (this project)")
ax.bar(x, [float(r["mae_last_value"]) for r in rows], w, color=S1, label="Last value")
ax.bar(x + w, [float(r["mae_seasonal_naive"]) for r in rows], w, color=REF, label="Same time yesterday")
ax.set_xticks(x, [r["workload"].replace(" (", "\n(") for r in rows]); ax.set_ylabel("MAE (requests/min)")
ax.set_title("30-minute-ahead forecast error (lower is better)", loc="left", color=INK); ax.legend(fontsize=8.5, ncol=3, loc="upper left"); ax.grid(axis="x", visible=False)
ax.set_ylim(0, max(float(r["mae_seasonal_naive"]) for r in rows) * 1.25)
fig.savefig(O + "fig_forecast_mae.png"); plt.close(fig)

# F5 capacity estimate
rows = rd("e5_capacity_estimate.csv"); x = np.arange(len(rows)); w = .36
fig, ax = plt.subplots(1, 2, figsize=(10, 3.2))
ax[0].bar(x - w/2, [float(r["estimated_max_vms"]) for r in rows], w, color=S3, label="Estimated (forecast module)")
ax[0].bar(x + w/2, [float(r["simulated_max_vms"]) for r in rows], w, color=S1, label="Simulated (AutoScaleSim)")
ax[0].set_xticks(x, [r["workload"].replace("NASA w", "W") for r in rows]); ax[0].set_ylabel("Max VMs"); ax[0].legend(fontsize=8.5); ax[0].set_title("Peak VMs", loc="left", color=INK)
ax[1].bar(x - w/2, [float(r["estimated_cost_inr"]) for r in rows], w, color=S3, label="Estimated")
ax[1].bar(x + w/2, [float(r["simulated_renting_cost_inr"]) for r in rows], w, color=S1, label="Simulated")
ax[1].set_xticks(x, [r["workload"].replace("NASA w", "W") for r in rows]); ax[1].set_ylabel("Renting cost (₹)"); ax[1].legend(fontsize=8.5); ax[1].set_title("Weekly renting cost", loc="left", color=INK)
for a in ax: a.grid(axis="x", visible=False)
fig.savefig(O + "fig_capacity_estimate.png"); plt.close(fig)

# F1 architecture
fig, ax = plt.subplots(figsize=(9, 4.6)); ax.set_xlim(0, 100); ax.set_ylim(0, 52); ax.axis("off")
def box(x, y, w, h, title, sub, ec, dashed=False):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.5", fc="#fbfbfa", ec=ec, lw=1.6, ls="--" if dashed else "-"))
    ax.text(x + 1.5, y + h - 3.2, title, fontsize=10.5, color=INK, weight="bold", va="top")
    ax.text(x + 1.5, y + h - 7.6, sub, fontsize=8.6, color=INK2, va="top")
def arrow(x1, y1, x2, y2): ax.annotate("", (x2, y2), (x1, y1), arrowprops=dict(arrowstyle="-|>", color=INK2, lw=1.2))
box(1, 1, 98, 50, "AWS EC2 instance (t3.small) · Docker container", "", REF, True)
box(3, 32, 22, 12, "Browser dashboard", "HTML · JS · Chart.js", S2)
box(32, 32, 31, 12, "Java web app + JSON API", "WebApp.java · job queue", S2)
box(69, 32, 28, 12, "Database", "SQLite volume / PostgreSQL", S2)
box(34, 17, 27, 10, "Extension layer", "BudgetGuard · Workload", S2)
box(18, 3, 62, 10, "AutoScaleSim (original, unchanged)", "CloudSim · Monitor · Analyzer · Planner · Executor", S1)
arrow(25.4, 38, 31.6, 38); arrow(63.4, 38, 68.6, 38); arrow(47.5, 31.6, 47.5, 27.4); arrow(47.5, 16.6, 47.5, 13.4)
ax.text(4, 27, "Orange: this project\nBlue: original authors", fontsize=8.5, color=INK2)
fig.savefig(O + "fig_architecture.png"); plt.close(fig)
print("ok")
