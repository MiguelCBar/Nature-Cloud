#!/bin/bash

source "$(dirname "$0")/config.sh"

aws ec2 deregister-image --delete-associated-snapshots --image-id $(cat image.id)