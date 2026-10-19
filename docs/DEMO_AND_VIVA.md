# Demo script and viva preparation

## Before you present (10 minutes earlier)

1. Open the deployed website (or run `scripts\start.bat` on your laptop as a backup) and check `…/api/health` shows `"status":"ok"`.
2. Run one **Reproduction** once so the Overview shows "Reproduction check: identical ✓".
3. Keep `samples\synthetic_flash_crowd_3days.csv` ready to upload.
4. Open `docs/AutoScaleSim_Lab_Presentation.pptx`. Speaker notes are under each slide.
5. Backup: if the internet fails, use the laptop (`scripts\start.bat`, <http://localhost:8080>). If Java fails too, show the screenshots in `docs/screenshots/`.

## Live demo (about 6 minutes)

| Step | Click | Say |
|---|---|---|
| 1 | **Overview** | "This is the original AutoScaleSim, unchanged, plus my three extensions. Orange in the diagram is mine, blue is the authors'. Everything is stored in the database." |
| 2 | **1 · Reproduction → Run simulation** (NASA) | "This runs the authors' own `main()` unchanged on 7 days of NASA traffic. It finishes in about 10 seconds. All three output files are byte-identical to the files the authors committed, so the reproduction is exact." |
| 3 | **2 · Budget guard**: budget 2000, target 4, rate 85 → **Run original vs guarded** | "Three real simulations: the original, the original with my guard only watching (identical), and the guard in control." |
| 4 | Point to the red warning and the KPI tiles | "The guard kept renting cost at ₹1995.80, under ₹2000, but average response time rose from 3.99 s to 8.90 s, so it warns that the budget and the 4-second target cannot both be met." |
| 5 | Charts | "Orange cost follows the dashed paced-budget line; blue (original) goes above it. Response time is the price we pay." |
| 6 | Explanations table, filter **CONFLICT**, read one row | "Every decision has a reason built from the actual numbers: delay, cost, allowance, target." |
| 7 | **3 · Workloads → Upload** the flash-crowd CSV | "I can upload my own traffic. It is validated and stored in the database." |
| 8 | Point to the history chart | "Blue is demand, green is the 30-minute-ahead forecast, red dots are spikes, yellow triangles are warnings. The KPI tiles show the honest backtest: recall 0.22 on this sudden synthetic spike, so sudden crowds are hard to predict." |
| 9 | Future chart and VMs chart | "Next 24 hours: forecast, upper bound and the VMs needed at 70 % CPU, with an estimated cost in rupees." |
| 10 | Tick **also run the budget guard**, budget 600 → **Run simulation** | "The same workload replayed in the unmodified simulator, with and without my guard." |
| 11 | **History** | "All runs and uploads come from the database, and each can be reopened." |

## Screenshot checklist (for the report or slides)

- [x] Overview with the database and reproduction KPIs: `docs/screenshots/01-overview.png`
- [x] Reproduction "Reproduced exactly": `02-reproduction.png`
- [x] Budget guard results with the conflict warning: `03-budget-guard.png`
- [x] Explanations table: `04-explanations.png`
- [x] Budget sweep: `05-sweep.png`
- [x] Workload forecast and spike warnings: `06-workload-forecast.png`
- [x] Workload replay with the guard: `07-workload-replay.png`
- [x] History from the database: `08-history.png`
- [x] Dark mode and mobile: `09-dark-budget.png`, `10-mobile.png`
- [ ] **AWS**: CloudFormation stack `CREATE_COMPLETE` with the Outputs tab (take this after you deploy)
- [ ] **AWS**: the website opened from its public `ec2-…amazonaws.com` address
- [ ] **AWS**: the EC2 instance list showing `autoscalesim-lab` running
- [ ] **GitHub**: your repository page showing the commits

## Viva questions and simple answers

**What paper did you reproduce?**
AutoScaleSim by Aslanpour, Toosi, Taheri and Gaire, *Simulation Modelling Practice and Theory*, 2021. It is a CloudSim-based toolkit for auto-scaling Web applications in a simulated cloud.

**How do you know your reproduction is correct?**
I ran the authors' unmodified `main()`. The three monitoring files it writes (10,080 rows each) are byte-identical to the files the authors committed to GitHub. An automated test checks this.

**Did you change the original code?**
No. `git diff` on `src/` is empty. I only supplied the missing libraries and compiled it for Java 8, as the authors' project specifies.

**Did you compare with the paper's tables?**
Not yet. The paper PDF could not be downloaded in my build environment, so I verified against the authors' own output files instead, which is a stricter check. It is listed as a limitation.

**How does the budget guard work without changing the algorithm?**
It is a subclass of the original planner. It calls the original rule first, then compares the cost already committed (using the simulator's own billing rule) with a paced budget, and either keeps the decision, blocks a scale-up, or removes one VM.

**What is a "paced" budget?**
The budget spread evenly over the week. After 1 day of 7, you may have spent about 1/7 of it. One hour of the minimum fleet is kept in reserve so the last VM can always be paid for.

**Why did total cost go up when you saved on VMs?**
AutoScaleSim also charges SLA penalties. Fewer VMs means slower responses and more violations, so the penalty grows faster than the renting cost falls. My dashboard shows this trade-off instead of hiding it.

**What does CONFLICT mean?**
The response-time target is being missed, and the budget does not allow adding capacity. The owner must either raise the budget or relax the target.

**What does INFEASIBLE mean?**
Even the minimum fleet (1 VM) costs more than the budget allows, so no configuration can meet it. At ₹500 a week this happens: one VM alone costs about ₹578.

**How are explanations generated? Is it AI?**
No. They are built from the numbers recorded at each decision (delay, response time, cost, allowance, target) using fixed, documented rules, so they are always true to the data.

**Which forecasting method did you use and why?**
Holt-Winters exponential smoothing with a daily season and damped trend, on 10-minute buckets. It is fast, well established and easy to explain. I compared it with two simple baselines.

**Is your forecast good?**
It is much better than "same time yesterday" and slightly better than "last value" at 20–60-minute leads on two of the three real weeks (about equal on the third). Spike warnings catch about half of the real spikes, with many false alarms. So it is useful for planning, not a perfect predictor.

**How does an uploaded CSV run in the original simulator?**
I convert it into the exact file format of the NASA trace, so the original emulator replays it unchanged. Arrival seconds inside each minute are drawn randomly with a fixed seed, matching the real trace.

**Is the replay exact?**
An exact copy of the NASA rows replays identically. A per-minute CSV loses the burstiness within each minute, so week 1 gives 5.1 s average response time instead of 3.99 s. I report this, and the dashboard warns that replays are indicative.

**Why SQLite and not MySQL or RDS?**
One server with one writer does not need a database server. SQLite is a real SQL database stored in a file on a persistent volume, with zero cost and administration. The same code also runs on PostgreSQL (tested), so Amazon RDS is one environment variable away.

**What is the cloud part?**
The dashboard runs in a Docker container on an AWS EC2 instance created by a CloudFormation template. The template only reports success after the website's health check answers. The auto-scaling itself is simulated; I do not claim to provision real VMs for the Web application.

**Why does every simulation run in a separate JVM?**
The original code keeps static state and reads and writes a relative folder, so two runs in one JVM would interfere. Separate processes keep each run clean, and a queue runs them one at a time.

**How did you test it?**
46 JUnit 5 tests: unit tests of every decision branch and of the billing rule, database tests on SQLite and PostgreSQL, API tests including bad inputs, and 8 integration tests that run real simulations. I also ran a browser walk-through of every feature and checked the Docker container's health.

**What would you do next?**
Compare against the paper's tables, use the forecast to save budget for predicted peaks, try learning-based predictors, and add HTTPS and login for a production deployment.
