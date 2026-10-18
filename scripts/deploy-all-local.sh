#!/bin/bash

# Abort if any command fails
set -e 

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

echo "======================================================"
echo "          NATURE@CLOUD - LOCAL MASTER PIPELINE        "
echo "======================================================"

echo "Step 1: Building entire Maven project..."
cd "$DIR/../" || exit
mvn clean install -DskipTests

echo -e "\nStep 2: Deploying AWS Lambda (FaaS)..."
cd "$DIR/aws/" || exit
./deploy-lambda.sh

echo -e "\nStep 3: Checking Worker Infrastructure..."

# Create AMI Prompt
read -p "Do you need to build/rebuild the Worker AMI? (Takes ~5-10 mins) (y/N): " BUILD_AMI
if [[ "$BUILD_AMI" =~ ^[Yy]$ ]]; then
    echo "Building Worker AMI and DynamoDB Tables..."
    cd "$DIR/aws/" || exit
    ./create-image.sh
else
    echo "Skipping AMI creation. Using the existing AMI."
fi

# Launch Template Prompt
read -p "Do you need to create/update the EC2 Launch Template? (y/N): " BUILD_TEMPLATE
if [[ "$BUILD_TEMPLATE" =~ ^[Yy]$ ]]; then
    cd "$DIR/aws/" || exit
    # Safety check: Ensure image.id exists before trying to build a template
    if [ ! -f "image.id" ]; then
        echo "[ERROR] Cannot create Launch Template: 'image.id' file not found."
        echo "You must build the AMI at least once on this machine to generate the ID."
        exit 1
    fi
    echo "Creating EC2 Launch Template..."
    ./launch-deployment-template.sh
else
    echo "Skipping Launch Template creation."
fi

echo -e "\nStep 4: Starting Master Node Locally..."
cd "$DIR/local/" || exit
./run-master-local.sh