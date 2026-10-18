#!/bin/bash

source "$(dirname "$0")/config.sh"

# Requesting an instance reboot to verify the rc.local script triggers on startup
aws ec2 reboot-instances --instance-ids $(cat instance.id)
echo "Rebooting instance to test web server auto-start."

# Letting the instance shutdown gracefully
sleep 1

# Wait for port 8000 to become available again
while ! nc -z $(cat instance.dns) 8000; do
	echo "Waiting for $(cat instance.dns):8000..."
	sleep 0.5
done

# Send a query to the root path instead of /test
echo "Sending a query to verify the server is responding!"
curl $(cat instance.dns):8000/