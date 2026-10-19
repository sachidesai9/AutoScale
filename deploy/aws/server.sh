#!/usr/bin/env bash
# Manage the running AutoScaleSim Lab server from AWS CloudShell (or any shell with the AWS CLI), through
# AWS Systems Manager: no SSH keys, no open ports besides HTTP.
#
#   bash server.sh update                                   pull latest main, rebuild, restart (data volume is kept)
#   bash server.sh history list                             list runs and workloads in the database
#   bash server.sh history delete-workloads --name-prefix qa_
#   bash server.sh history delete-run <id>
#   bash server.sh history clear-runs                       delete all runs (workloads are kept)
#   bash server.sh logs                                     last 80 lines of the application log
#
# Environment: AWS_REGION (default ap-southeast-2), STACK_NAME (default autoscalesim-lab), DRY_RUN=1 to preview.
set -euo pipefail
REGION=${AWS_REGION:-ap-southeast-2}
STACK=${STACK_NAME:-autoscalesim-lab}
ACTION=${1:?usage: server.sh update | history ... | logs}
shift || true

if [ -n "${DRY_RUN:-}" ]; then ID=i-dry-run; else
  ID=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)
  [ -n "$ID" ] && [ "$ID" != "None" ] || { echo "Stack $STACK not found in $REGION"; exit 1; }
fi

case "$ACTION" in
  update)
    SCRIPT='set -e
cd /opt/autoscalesim
git fetch --depth 1 origin main
git reset --hard origin/main
docker build -t autoscalesim-lab:latest .
HEAP=$(docker inspect --format "{{range .Config.Env}}{{println .}}{{end}}" autoscalesim 2>/dev/null | sed -n "s/^SIM_HEAP=//p")
docker rm -f autoscalesim || true
docker run -d --name autoscalesim --restart unless-stopped -p 80:8080 -e SIM_HEAP=${HEAP:-1g} -v autoscalesim-data:/data autoscalesim-lab:latest
for i in $(seq 1 40); do curl -fsS http://127.0.0.1/api/health && echo && echo "Updated to $(git rev-parse --short HEAD)" && exit 0; sleep 3; done
echo "health check failed"; docker logs --tail 50 autoscalesim; exit 1' ;;
  history)
    ARGS=""
    for a in "$@"; do
      [[ "$a" =~ ^[A-Za-z0-9_.:-]+$ ]] || { echo "unsupported argument: $a"; exit 1; }
      ARGS="$ARGS $a"
    done
    SCRIPT="docker exec autoscalesim java -cp dist/autoscalesim-lab.jar budgetguard.BudgetGuard history${ARGS:- list} --data-dir /data" ;;
  logs)
    SCRIPT='docker ps --filter name=autoscalesim; docker logs --tail 80 autoscalesim' ;;
  *) echo "unknown action: $ACTION"; exit 1 ;;
esac

PARAMS=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.stdin.read().split("\n")}))' <<<"$SCRIPT")
if [ -n "${DRY_RUN:-}" ]; then echo "$PARAMS"; exit 0; fi   # DRY_RUN=1: show what would be sent, do nothing
CMD=$(aws ssm send-command --region "$REGION" --instance-ids "$ID" --document-name AWS-RunShellScript \
  --comment "autoscalesim $ACTION" --timeout-seconds 1200 --parameters "$PARAMS" --query Command.CommandId --output text)
echo "Sent '$ACTION' to $ID (command $CMD); waiting..."
for i in $(seq 1 240); do
  STATUS=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CMD" --instance-id "$ID" --query Status --output text 2>/dev/null || echo Pending)
  case "$STATUS" in Pending|InProgress|Delayed) sleep 5 ;; *) break ;; esac
done
aws ssm get-command-invocation --region "$REGION" --command-id "$CMD" --instance-id "$ID" \
  --query '[StandardOutputContent,StandardErrorContent]' --output text
echo "Status: $STATUS"
[ "$STATUS" = "Success" ]
