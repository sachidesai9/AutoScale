# Tools used and their purpose

> **Rubric mapping pending.** The rubric image referred to in the project brief was not available in the build session, so this table lists every tool actually used. Once the rubric is available, add a column mapping each required tool to the row below, and add anything missing.

| Step | Tool | Version | Purpose in this project |
|---|---|---|---|
| Base simulator | AutoScaleSim (authors' Java code) | commit 9a0febb | Original simulator, reproduced unchanged |
| Simulation engine | CloudSim (bundled in AutoScaleSim) | as shipped | Discrete-event cloud simulation |
| Language / runtime | Java (JDK) | 17+ (tested 21) | Original code (`--release 8`) and extension (`--release 17`) |
| Original build | Apache Ant / NetBeans project (authors') | n/a | Documented; replaced by `scripts/build.*` because the project pointed to JARs on the authors' disk |
| Build scripts | Bash, PowerShell, `javac`, `jar` | — | One-command build on Linux/macOS and Windows |
| Libraries | Apache Commons Math 3.5, Apache POI 3.8, Neuroph 2.92 | — | Required by the original code |
| Database | SQLite (sqlite-jdbc 3.46) / PostgreSQL (JDBC 42.7, server 16) | — | Runs, metrics, uploaded workloads |
| Web server / API | JDK `HttpServer` | built in | Dashboard and JSON API without extra frameworks |
| Frontend | HTML, CSS, JavaScript, Chart.js | 4.4.6 | Interactive dashboard and charts |
| Testing | JUnit 5 (Platform Console Standalone) | 1.11.3 | 54 unit, API, database and integration tests |
| UI testing | Playwright (Chromium) | — | End-to-end browser walk-through and screenshots |
| Packaging | Docker, Docker Compose | — | Container image with health check |
| Cloud | AWS EC2, CloudFormation, IAM, Systems Manager | — | Public deployment, infrastructure as code, logs |
| Infrastructure lint | cfn-lint | — | Validates the CloudFormation template |
| Version control | Git, GitHub | — | Original history preserved; extension on top |
| Editor | Visual Studio Code | — | Recommended editor (any editor works) |
| Figures | Python matplotlib | 3.11 | Report figures from `docs/results/*.csv` |
| Documents | docx (Node), pptxgenjs | — | Report and slides generated from the real results |
