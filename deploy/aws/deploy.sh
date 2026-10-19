#!/usr/bin/env bash
# Deploys (or updates) the stack with the AWS CLI and verifies the health endpoint.
# Usage: deploy/aws/deploy.sh https://github.com/<you>/<repo>.git [branch] [region]
# Needs: AWS CLI v2 configured with your own credentials (aws configure). Never commit credentials.
set -euo pipefail
REPO=${1:?repository URL required}; BRANCH=${2:-main}; REGION=${3:-${AWS_REGION:-ap-southeast-2}}
STACK=${STACK_NAME:-autoscalesim-lab}
cd "$(dirname "$0")"
aws cloudformation deploy --region "$REGION" --stack-name "$STACK" --template-file autoscalesim-lab.yaml \
  --capabilities CAPABILITY_IAM --parameter-overrides RepoUrl="$REPO" Branch="$BRANCH"
URL=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query "Stacks[0].Outputs[?OutputKey=='WebsiteURL'].OutputValue" --output text)
echo "Website: $URL"
curl -fsS "${URL}api/health" && echo && echo "Deployment verified."
