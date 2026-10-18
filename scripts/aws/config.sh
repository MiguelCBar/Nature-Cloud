#!/bin/bash

# Get the directory where the script is located
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Go up two directories from scripts/aws/ to reach the project root
ENV_FILE="$DIR/../../.env"

# -----------------------------------------------------------------------------
# LOAD AWS CREDENTIALS & CONFIGURATION FROM .env FILE
# -----------------------------------------------------------------------------

# Check if the .env file exists before attempting to load it
if [ -f "$ENV_FILE" ]; then
    # Enable automatic exporting of variables
    set -a
    
    # Source the .env file to load its contents
    source "$ENV_FILE"
    
    # Disable automatic exporting
    set +a
else
    echo "Error: .env file not found in $DIR!"
    echo "Please create a .env file based on the required template."
    exit 1
fi

# Export PATH (modify if you need a specific AWS CLI path)
export PATH=$PATH: