# Deployment guide

The application is one Docker container: a Java web server, the original AutoScaleSim, and a SQLite database on a volume. It must run on a machine that can execute Java, because every button in the dashboard starts real simulations. A static website host cannot run it.

| Option | Use it for | Public URL | Cost |
|---|---|---|---|
| A. Laptop (`scripts\start.bat`) | Development, backup demo | No | Free |
| B. Docker on any machine | Same as the cloud, locally | No | Free |
| C. **AWS EC2 via CloudFormation** | The required cloud deployment | **Yes** | Instance + disk + public IPv4 (see "Cost") |

## Architecture on AWS

```
Browser ──HTTP:80──▶ EC2 instance (Amazon Linux 2023, t3.small)
                      └─ Docker container "autoscalesim"
                           ├─ Java web app + API (WebApp.java)  ──▶ SQLite on Docker volume "autoscalesim-data"
                           └─ child JVMs running AutoScaleSim (one simulation at a time)
```

The CloudFormation template `deploy/aws/autoscalesim-lab.yaml` creates:

| Resource | Purpose |
|---|---|
| Security group | Allows HTTP (port 80) only. There is no SSH; administration goes through AWS Systems Manager. |
| IAM role + instance profile | `AmazonSSMManagedInstanceCore`, so you can open a browser shell (Session Manager) for logs. |
| EC2 instance (default t3.small, 16 GB encrypted gp3, IMDSv2 required) | Installs Docker and Git, clones your repository, builds the image and runs the container with a persistent volume. |

The instance has a **CreationPolicy**: the stack only reaches `CREATE_COMPLETE` when the startup script has received `{"status":"ok"}` from `/api/health`. If the build or the app fails, the stack rolls back and shows the reason. A green stack therefore means the website was verified to be running.

## C1. Deploy from the AWS Console (no command line)

Prerequisites:

- An AWS account, signed in to the console. Use your own login; never share keys or passwords.
- This project in a **public** GitHub repository (the instance clones it).

Steps:

1. Choose the region at the top right: **Asia Pacific (Sydney) ap-southeast-2** (the project account uses this region).
2. Open **CloudFormation → Create stack → With new resources (standard)**.
3. Select **Upload a template file**, choose `deploy/aws/autoscalesim-lab.yaml`, then click **Next**.
4. Stack name: `autoscalesim-lab`. Parameters:
   - **RepoUrl**: `https://github.com/sachidesai9/autoscale.git`
   - **Branch**: `main`
   - **AlertEmail**: your e-mail address (recommended; AWS sends a confirmation e-mail you must accept)
   - **MonthlyBudgetUsd**: `20`
   - Leave the others at their defaults (`t3.small`, `SimHeap 1g`, `AllowedCidr 0.0.0.0/0`).
5. Click **Next** twice, then tick **"I acknowledge that AWS CloudFormation might create IAM resources"** and click **Submit**.
6. Wait for **CREATE_COMPLETE** (usually 5–10 minutes).
7. Open the **Outputs** tab and click **WebsiteURL**.

## C2. Deploy with the AWS CLI

You need the AWS CLI v2 installed and configured with `aws configure`.

```bash
deploy/aws/deploy.sh https://github.com/sachidesai9/autoscale.git main ap-southeast-2
# prints the WebsiteURL and calls /api/health
```

## Verify

1. `http://<WebsiteURL>/api/health` returns `{"status":"ok","database":"SQLite",...}`.
2. In the dashboard: **1 · Reproduction → Run simulation** finishes in about 10–20 s and shows "Reproduced exactly".
3. **History** lists the run. Reboot the instance (EC2 → Instance state → Reboot); the run is still listed, because data lives on the Docker volume.

## Logs and troubleshooting

Open EC2 → select the instance → **Connect → Session Manager → Connect**, then:

```bash
sudo tail -n 100 /var/log/autoscalesim-setup.log      # what the startup script did
sudo docker ps                                         # container should be "healthy"
sudo docker logs --tail 100 autoscalesim               # application log
curl -s localhost/api/health
```

| Symptom | Cause / fix |
|---|---|
| Stack ROLLBACK with "setup script failed" | Usually a wrong RepoUrl/Branch, or the repository is private. Fix the parameter and create the stack again. |
| Stack ROLLBACK with "did not answer /api/health" | Check `docker logs`. On t3.micro, choose t3.small. |
| Page does not open | Use `http://`, not `https://`. Check that AllowedCidr includes your IP. |
| "The simulation queue is full" | Three runs are already waiting. Wait a minute; simulations run one at a time. |
| A run fails with OutOfMemoryError | Update the stack with a larger instance, or lower `SimHeap` to `768m`. |

## Update to a new version

Push to GitHub, then either update the stack (any parameter change re-creates the instance; keep the old one to preserve data) or, in Session Manager:

```bash
cd /opt/autoscalesim && sudo git pull && sudo docker build -t autoscalesim-lab:latest . \
 && sudo docker rm -f autoscalesim && sudo docker run -d --name autoscalesim --restart unless-stopped \
    -p 80:8080 -v autoscalesim-data:/data autoscalesim-lab:latest
```

## Cost

You pay while the stack exists, for:

- the **EC2 instance** (on-demand, billed per second);
- the **16 GB gp3 disk**;
- the **public IPv4 address**.

Check the current prices for your region in the [AWS Pricing Calculator](https://calculator.aws/). Accounts created after 15 July 2025 receive Free Plan credits that EC2 usage draws down; check your balance under **Billing → Credits**. **Credits do not necessarily cover everything**, so set a budget alert (Billing → Budgets → Create budget → Zero spend or monthly cost budget).

### Staying within US$100 of credits

The stack contains one t3.small instance, a 16 GB disk and one public IPv4 address. At on-demand list prices in Sydney this is in the order of **US$20–30 per month** if left running 24/7. Confirm the figure for your region in the calculator before relying on it.

- Running continuously until grading uses roughly a quarter of the credits per month.
- Stopping the instance when you are not demonstrating cuts most of that: you then pay only for the disk and, while it is assigned, the IP address.
- `t3.micro` roughly halves the instance cost, but simulations are slower (the template adds a 2 GB swap file for it).
- The optional **AlertEmail** budget (US$20 per month by default, before credits) e-mails you at 80 % of actual spend and when the forecast passes 100 %.
- No other paid services are used: no load balancer, no NAT gateway, no RDS.

Ways to reduce cost:

- **Stop** the instance between demos (EC2 → Instance state → Stop). Compute charges stop, but the disk is still billed and the public DNS name changes after a restart.
- **Delete** the stack after grading (see below).

## Remove everything

- **Console:** CloudFormation → select `autoscalesim-lab` → **Delete**. This removes the instance, disk, security group and IAM role.
- **CLI:** `deploy/aws/teardown.sh ap-southeast-2`

All data on the instance is deleted with it. Download the reports you need from the History tab first.

## Optional: PostgreSQL (Amazon RDS) instead of SQLite

SQLite is the right fit for one server with one writer. If a managed database is required, create an RDS PostgreSQL instance in the same VPC and allow port 5432 from the instance's security group. Then run the container with:

```
-e DATABASE_URL=jdbc:postgresql://<rds-endpoint>:5432/<db> -e DB_USER=<user> -e DB_PASSWORD=<password>
```

The schema is created automatically. RDS adds a separate hourly charge.
