#!/bin/bash

source "$(dirname "$0")/config.sh"

# Step 2: delete Launch Template.
aws ec2 delete-launch-template \
  	--launch-template-name CNV-LaunchTemplate

# Step 4: delete DynamoDB Tables
aws dynamodb delete-table \
  --table-name NatureRequestLog

aws dynamodb wait table-not-exists \
  --table-name NatureRequestLog

# Step 5: delete DynamoDB Tables
aws dynamodb delete-table \
  --table-name NatureMetricsAgg

aws dynamodb wait table-not-exists \
  --table-name NatureMetricsAgg