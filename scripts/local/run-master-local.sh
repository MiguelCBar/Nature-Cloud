#!/bin/bash

# Source the configuration to load AWS credentials and variables from .env
source "$(dirname "$0")/../aws/config.sh"

echo "Starting Master Node Locally on port $LOAD_BALANCER_PORT..."
# Navigate to the master module directory
cd "$DIR/../../master" || exit

# Run the Master locally. It will connect to AWS using your local credentials/env vars
java -jar target/master-1.0.0-SNAPSHOT-jar-with-dependencies.jar