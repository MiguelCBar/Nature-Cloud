#!/bin/bash

# Source the configuration to load AWS credentials and variables from .env
source "$(dirname "$0")/config.sh"

echo "Launching Master EC2 Instance..."
aws ec2 run-instances \
	--image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
	--instance-type t3.micro \
	--key-name "$AWS_KEYPAIR_NAME" \
	--security-group-ids "$AWS_SECURITY_GROUP" \
	--iam-instance-profile Name="$AWS_MASTER_IAM_PROFILE" \
	--tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=CNV-Master-Node}]' \
	--monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > master-instance.id

MASTER_ID=$(cat master-instance.id)
echo "Master instance launched with ID: $MASTER_ID"

echo "Waiting for instance to be running..."
aws ec2 wait instance-running --instance-ids "$MASTER_ID"

# Get Public DNS to connect
aws ec2 describe-instances --instance-ids "$MASTER_ID" | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > master-instance.dns
MASTER_DNS=$(cat master-instance.dns)

echo "Master instance is running at $MASTER_DNS"

# Wait for SSH to become available
while ! nc -z "$MASTER_DNS" 22; do
	echo "Waiting for SSH on $MASTER_DNS:22..."
	sleep 1
done
echo "SSH is ready!"

# Install Java 11 on the Master VM
echo "Installing Java 11 on Master VM..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$MASTER_DNS" "sudo yum update -y && sudo yum install java-11-amazon-corretto-devel.x86_64 -y"

# Upload the compiled Master JAR and the .env file (Crucial: the Master needs the .env variables to function!)
echo "Uploading Master JAR and configuration..."
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$DIR/../../master/target/master-1.0.0-SNAPSHOT-jar-with-dependencies.jar" ec2-user@"$MASTER_DNS":/home/ec2-user/master.jar
scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" "$ENV_FILE" ec2-user@"$MASTER_DNS":/home/ec2-user/.env

# Start the Master Node using a background process (nohup) so it keeps running after the SSH session closes
echo "Starting Master Daemon..."
ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@"$MASTER_DNS" "set -a; source /home/ec2-user/.env; set +a; nohup java -jar /home/ec2-user/master.jar > /home/ec2-user/master.log 2>&1 &"

echo "======================================================"
echo "SUCCESS! Master Node is running."
echo "You can route HTTP traffic to: http://$MASTER_DNS:$LOAD_BALANCER_PORT/"
echo ""
echo "To check the live logs, SSH into the instance:"
echo "ssh -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$MASTER_DNS"
echo "tail -f /home/ec2-user/master.log"
echo "======================================================"