#!/bin/bash

source "$(dirname "$0")/config.sh"
DIR="$(dirname "$0")"
# Step 1: launch a vm instance.
$DIR/launch-vm.sh

# Step 2: install software in the VM instance.
$DIR/install-vm.sh

# Step 3: test VM instance.
$DIR/test-vm.sh

# Step 4: create VM image (AIM).
aws ec2 create-image --instance-id $(cat instance.id) --name CNV-Image | jq -r .ImageId > image.id
echo "New VM image with id $(cat image.id)."

# Step 5: Wait for image to become available.
echo "Waiting for image to be ready... (this can take a couple of minutes)"
aws ec2 wait image-available --filters Name=name,Values=CNV-Image
echo "Waiting for image to be ready... done! \o/"

# Step 6: terminate the vm instance.
aws ec2 terminate-instances --instance-ids $(cat instance.id)

# Step 7: Create needed DynamoDB tables.
echo "Creating DynamoDB Metrics Tables."
aws dynamodb create-table \
  --table-name NatureRequestLog \
  --attribute-definitions \
      AttributeName=requestId,AttributeType=S \
  --key-schema \
      AttributeName=requestId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

aws dynamodb create-table \
  --table-name NatureMetricsAgg \
  --attribute-definitions \
      AttributeName=operation,AttributeType=S \
      AttributeName=parameterBucket,AttributeType=S \
  --key-schema \
      AttributeName=operation,KeyType=HASH \
      AttributeName=parameterBucket,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST
echo "Creation of tables complete."