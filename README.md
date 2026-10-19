# AutoScaleSim Lab

Repository: <https://github.com/sachidesai9/autoscale>

**Reproduction and Extension of AutoScaleSim for Budget-Aware, Explainable and Predictive Cloud Auto-Scaling**

This repository is built on the original **AutoScaleSim** simulator by Aslanpour, Toosi, Taheri and Gaire:

> M. S. Aslanpour, A. N. Toosi, J. Taheri and R. Gaire, "AutoScaleSim: A simulation toolkit for auto-scaling Web applications in clouds," *Simulation Modelling Practice and Theory*, vol. 108, art. 102245, 2021. doi: [10.1016/j.simpat.2020.102245](https://doi.org/10.1016/j.simpat.2020.102245) · Original code: <https://github.com/aslanpour/AutoScaleSim>

The authors' Java sources in `src/` are **unchanged** (check with `git diff 9a0febb -- src`). Everything added by this project lives in `extension/`, `scripts/`, `deploy/`, `samples/` and `docs/`.

| Part | Who | Where |
|---|---|---|
| AutoScaleSim simulator: MAPE loop, scaling rules, executors, cost model, NASA and Wikipedia traces | Original authors | `src/` |
| 1. Reproduction runner and output-identity checks | This project | `extension/src/budgetguard/BudgetGuard.java`, `Evaluate.java` |
| 2. Budget-aware, explainable auto-scaling | This project | `BudgetGuard.java` (planner subclass, explanations, warnings) |
| 3. Workload upload, forecasting, spike warnings, capacity and cost | This project | `Workload.java` |
| Web dashboard, JSON API, database | This project | `WebApp.java`, `Store.java`, `extension/web/` |
| Docker image, AWS CloudFormation deployment | This project | `Dockerfile`, `docker-compose.yml`, `deploy/aws/` |
| JUnit 5 tests (54) | This project | `extension/test/` |

## Quick start (Windows, for the demo)

You need **JDK 17 or newer** (for example Eclipse Temurin 21). Nothing else is downloaded: all libraries are in `lib/`.

```powershell
cd AutoScaleSim-Lab                 # the folder you unzipped or cloned
scripts\start.bat                   # builds on first run, then serves the dashboard
```

Open <http://localhost:8080/>. Press `Ctrl+C` in the window to stop. Data (database, run results) is kept in `data\`.

Linux or macOS: `scripts/start.sh`.

### Docker (any OS)

```bash
docker compose up -d --build        # http://localhost:8080
docker compose logs -f              # logs
docker compose down                 # stop (data stays in the "appdata" volume)
```

### AWS (public website)

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): one CloudFormation stack creates an EC2 instance that builds and runs the Docker image. The stack only finishes successfully after the website's health check answers.

## The three parts

### 1. Reproduction

`Reproduction` tab, or `java -jar dist/autoscalesim-lab.jar run --mode original` in a working folder. It runs the authors' `autoscalesim.ExperimentalSetup.main()` exactly as published (NASA trace, 7 days, SLA-aware rule, t2.medium, scaling every 10 minutes). It then checks that the three monitoring files it writes are byte-identical to the ones the authors committed: they are.

| Metric (NASA, 7 days) | Value |
|---|---|
| Requests | 567,783 |
| Average response time | 3.986 s |
| SLA violation | 29.617 % |
| Max VMs | 8 |
| Scale-up / contradictory decisions | 306 / 279 |
| Renting cost / SLA penalty / total | US$29.84 / US$11.24 / US$41.08 |

**Comparison with the paper:** the paper PDF could not be downloaded from the build environment, so its reported figures have not been transcribed yet. See `extension/web/paper_results.json`.

### 2. Budget-aware, explainable auto-scaling

`GuardedPlanner` extends the original `PlannerRuleBased`. The original rule decides first; the guard then:

1. computes the committed renting cost with the simulator's own billing rule (each VM: `ceil(hours) × hourly price`), looking ahead to the next decision;
2. compares it with a **paced budget** (budget × elapsed fraction, minus one billed hour reserved for the minimum fleet);
3. blocks a scale-up that is not affordable, or removes one VM when spending runs ahead of the budget;
4. writes a plain-English **explanation** of each decision with a status: `OK`, `PERF_MISS`, `BUDGET_HOLD`, `CONFLICT` (target missed and budget blocks capacity), `BUDGET_RECOVERY`, `INFEASIBLE` (no configuration fits the budget).

Three result types are always shown side by side:

1. **Original AutoScaleSim**: the authors' run.
2. **Original, analysed by the extension**: same simulation, the guard only observes (tested byte-identical to 1).
3. **Budget-guarded policy**: a separate simulation in which the guard's decisions are executed.

### 3. Custom workloads and predictive spike warnings

- **Upload** a CSV, either per-minute counts (`minute,requests`) or a request log (one `timestamp` per row). Limits: 120 minutes to 7 days, at most 2000 requests per minute.
- **Replay** in AutoScaleSim: the CSV is converted to the exact `Dataset_NASA_July.tset` format, so the unmodified emulator replays it. Arrival seconds inside each minute are drawn uniformly at random (fixed seed) to match the measured trace.
- **Forecast**: Holt-Winters additive smoothing with a damped trend and a daily season, on 10-minute buckets. Parameters are chosen by grid search and compared with last-value and seasonal-naive baselines. The fit is burst-robust: one-off bursts of up to 1 hour are excluded from the trend and daily season (and an ongoing burst fades at a fitted rate), so a single spike cannot inflate future forecasts.
- **Spikes and warnings**: a bucket is a spike when it is above the trailing 2-hour mean + k·σ. A warning fires when the forecast's 90 % upper bound crosses that threshold ahead of time, and the warnings are backtested for precision and recall.
- **Capacity and cost**: one t2.medium completes 30 requests per minute at 100 % CPU (from the simulator's MIPS, PEs and request length). Required VMs are based on a 70 % target utilisation, and cost uses the simulator's US$0.04 per hour price.

## Evaluation

`java -jar dist/autoscalesim-lab.jar evaluate` regenerates every table in [docs/results/RESULTS.md](docs/results/RESULTS.md) in about 2.5 minutes:

- **E1:** reproduction.
- **E2:** budget sweep from ₹500 to ₹3000.
- **E3:** fidelity of replaying per-minute workloads.
- **E4:** forecast and spike-warning backtest on real NASA weeks 2–4 and a labelled synthetic flash crowd.
- **E5:** capacity and cost estimate compared with simulation.

## Tests

```bash
scripts/test.sh all          # Windows: scripts\test.ps1 all   (unit + integration, about 1 minute)
scripts/test.sh unit         # fast tests only
```

Last verified run: **54 tests, 54 passed** (including PostgreSQL 16 when `TEST_POSTGRES_URL` is set, and integration tests that run real simulations).

## Database

The database stores simulation runs, per-variant metrics, and uploaded workloads (metadata plus the normalised CSV).

- **Default: SQLite** (`data/autoscalesim.db`). A single server with low write volume needs no database server, administration or cost.
- **Optional: PostgreSQL** (for example Amazon RDS). Set `DATABASE_URL=jdbc:postgresql://host:5432/db`, `DB_USER` and `DB_PASSWORD`. The same SQL runs on both.

## Command line

```text
java -jar dist/autoscalesim-lab.jar <command>
  serve    [--port 8080] [--data-dir data]           web dashboard + API
  compare  --budget-inr 2000 --target-rt 4.0         original vs analysed vs guarded + HTML report
  sweep    --budgets 750,1000,1500 --target-rt 4.0   evaluation over budgets
  analyse  --csv file.csv                            forecast and spike analysis (JSON)
  simulate --csv file.csv [--guard]                  replay a workload in AutoScaleSim
  evaluate [--out docs/results]                      all experiments in the report
  samples  [--out samples]                           regenerate sample workloads
```

## Compatibility notes (the only changes needed to run the original code)

- The authors' NetBeans project points to JARs on their own disk (`D:\Dropbox\...`). The same libraries are provided in `lib/`. Neuroph 2.9 is not on Maven Central, so the repackaged `org.beykery:neuroph:2.92` is used; the byte-identical outputs show it reads the NASA trace correctly.
- The original sources are compiled with `--release 8`, as `nbproject/project.properties` specifies. The extension needs Java 17.
- The original code reads and writes the relative folder `src/others/` and keeps static state, so every simulation runs in its own JVM and working folder.

## Licences

The original repository has no licence file; its README says "Feel free to use it and cite." Cite the paper above. Bundled libraries: Apache Commons Math and Apache POI (Apache-2.0), Neuroph (Apache-2.0), sqlite-jdbc (Apache-2.0), PostgreSQL JDBC (BSD-2-Clause), SLF4J (MIT), Chart.js (MIT), JUnit 5 (EPL-2.0).

---

### Original README (by the AutoScaleSim authors)

> This is a simulator written in Java for auto-scaling of Web applications in cloud.
> The whole architecture is explained in the published paper as:
> https://www.sciencedirect.com/science/article/abs/pii/S1569190X20301738
> Feel free to use it and cite.
