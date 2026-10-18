#!/bin/bash

source "$(dirname "$0")/config.sh"

# Create the Launch Template using the previously generated AMI
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat image.id)\",
		\"InstanceType\": \"t3.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"IamInstanceProfile\": {
    		\"Name\": \"$IAM_INSTANCE_PROFILE_NAME\"
  		},
		\"Monitoring\": {\"Enabled\": true}
	}"