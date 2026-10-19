#!/usr/bin/env bash
# Deletes every resource created by the stack (instance, disk, security group, IAM role). Data on the instance is lost.
set -euo pipefail
REGION=${1:-${AWS_REGION:-ap-south-1}}; STACK=${STACK_NAME:-autoscalesim-lab}
aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --region "$REGION" --stack-name "$STACK"
echo "Stack $STACK deleted."
