#!/bin/bash

# Abort the script immediately if any command fails
set -e 

source "$(dirname "$0")/config.sh"
DIR="$(dirname "$0")"

# Navigate to the ROOT of your project so the zip-file path works
cd "$DIR/../../" || exit

echo "======================================================"
echo "1. Setting up AWS IAM Roles..."
echo "======================================================"
# Check if the role already exists to prevent EntityAlreadyExists errors
if aws iam get-role --role-name NatureLambdaExecutionRole > /dev/null 2>&1; then
    echo "Role 'NatureLambdaExecutionRole' already exists. Skipping creation."
else
    echo "Creating NatureLambda Execution Role..."
    aws iam create-role \
        --role-name NatureLambdaExecutionRole \
        --assume-role-policy-document '{"Version": "2012-10-17","Statement": [{ "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'

    echo "Waiting for role propagation..."
    sleep 10

    echo "Attaching Basic Execution Policy..."
    aws iam attach-role-policy \
        --role-name NatureLambdaExecutionRole \
        --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

    echo "Waiting for policy propagation..."
    sleep 10
fi

echo "======================================================"
echo "2. Deploying NatureLambda Function..."
echo "======================================================"

# Check if the Lambda already exists. If it does, update the code. If not, create it.
if aws lambda get-function --function-name NatureLambda > /dev/null 2>&1; then
    echo "Lambda function exists. Updating code..."
    aws lambda update-function-code \
        --function-name NatureLambda \
        --zip-file fileb://faas-worker/target/faas-worker-1.0.0-SNAPSHOT-jar-with-dependencies.jar
else
    echo "Lambda function does not exist. Creating new function..."
    aws lambda create-function \
        --function-name NatureLambda \
        --zip-file fileb://faas-worker/target/faas-worker-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
        --handler pt.ulisboa.tecnico.cnv.faas.LambdaRouter::handleRequest \
        --runtime java11 \
        --timeout 30 \
        --memory-size 512 \
        --role arn:aws:iam::$AWS_ACCOUNT_ID:role/NatureLambdaExecutionRole
fi

echo "======================================================"
echo "SUCCESS! NatureLambda has been deployed to AWS."
echo "Your FaaS infrastructure is ready to receive requests."
echo "======================================================"