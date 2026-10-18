#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "$DIR/aws/" || exit

# Load environment variables
source config.sh

echo "======================================================"
echo "          NATURE@CLOUD - FULL TEARDOWN PIPELINE       "
echo "======================================================"

echo -e "\nStep 1: Terminating Master EC2 Instance..."
if [ -f "master-instance.id" ]; then
    MASTER_ID=$(cat master-instance.id)
    echo "Terminating Master Node ($MASTER_ID)..."
    aws ec2 terminate-instances --instance-ids "$MASTER_ID" >/dev/null 2>&1 || true
    rm master-instance.id master-instance.dns
else
    echo "No master-instance.id file found. Assuming already terminated."
fi

echo -e "\nStep 2: Terminating Worker EC2 Instances..."
# Dynamically find all active instances that were launched using Launch Template
WORKER_IDS=$(aws ec2 describe-instances \
    --filters "Name=instance-state-name,Values=pending,running,stopping,stopped" "Name=tag:aws:ec2launchtemplate:name,Values=CNV-LaunchTemplate" \
    --query "Reservations[*].Instances[*].InstanceId" --output text)

if [ -n "$WORKER_IDS" ]; then
    echo "Terminating Worker Nodes: $WORKER_IDS"
    aws ec2 terminate-instances --instance-ids $WORKER_IDS >/dev/null 2>&1 || true
else
    echo "No active worker instances found."
fi

echo -e "\nStep 3: Deleting AWS Lambda Function & Role..."
aws lambda delete-function --function-name NatureLambda >/dev/null 2>&1 || echo "Lambda already deleted."
# The Role must be detached from the policy before it can be deleted
aws iam detach-role-policy --role-name NatureLambdaExecutionRole --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole >/dev/null 2>&1 || true
aws iam delete-role --role-name NatureLambdaExecutionRole >/dev/null 2>&1 || true

echo -e "\nStep 4: Deleting Launch Template & DynamoDB Tables..."
if [ -f "terminate-deployment-template.sh" ]; then
    ./terminate-deployment-template.sh || true
fi

echo -e "\nStep 5: Deregistering Worker AMI..."
if [ -f "deregister-image.sh" ] && [ -f "image.id" ]; then
    ./deregister-image.sh || true
    rm image.id
else
    echo "AMI script or image.id missing. Skipping."
fi

echo "======================================================"
echo "TEARDOWN COMPLETE! All provisioned resources destroyed."
echo "Note: EC2 instances may show as 'terminated' in the AWS"
echo "console for about an hour before completely disappearing."
echo "======================================================"