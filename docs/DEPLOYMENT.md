# Nature@Cloud Deployment Guide

This guide takes you from an empty AWS account to a running cluster: the auto-scaling EC2 workers, the AWS Lambda function, the DynamoDB metrics tables and the master node. For a description of each script, see [scripts/README.md](../scripts/README.md).

> **Cost warning:** the deployment creates billable AWS resources. Always finish with the [teardown](#step-5-teardown).

## Prerequisites

Install these on your machine first:

- **Java 11** (Amazon Corretto recommended)
- **Apache Maven** (`mvn`)
- **AWS CLI v2**, configured with your credentials (`aws configure`)
- **jq** (command-line JSON processor)
- **nc** (netcat, used for network checks)
- **tmux** (optional, only for `scripts/stress_buckets_tmux.sh`)

## Step 1: Manual AWS Console setup

Create these resources in the AWS Management Console before running any script.

### 1. IAM access keys and roles

Create CLI access keys and the `WorkerInstanceProfile` and `MasterInstanceProfile` roles.

> **Important:** follow [AWS_SETUP.md](AWS_SETUP.md) exactly before continuing.

### 2. EC2 key pair

1. Go to **EC2 > Key Pairs** and create a key pair, for example `CNV-Key`.
2. Download the `.pem` file and keep it somewhere safe.
3. Restrict its permissions:
   ```bash
   chmod 400 /path/to/your/key.pem
   ```

### 3. Security group

Create a security group, for example `CNV-SG`, with these inbound rules:

| Port | Protocol | Source | Why |
|---|---|---|---|
| 22 | SSH | **Your IP address** | Lets the scripts install software on the VMs |
| 8000 | HTTP | Your IP address, and the security group itself | Lets you send requests to the master, and lets the master and workers talk to each other |

Avoid `0.0.0.0/0` where you can. It exposes SSH and the web servers to the whole internet. If you must use it to get started, close it again when you finish.

## Step 2: Environment configuration (`.env`)

Copy the template to the project root and fill in your values:

```bash
cp .env.example .env
```

Example values:

```bash
# AWS credentials
AWS_DEFAULT_REGION="us-east-1"
AWS_ACCOUNT_ID="123456789012"
AWS_ACCESS_KEY_ID="AKIA..."
AWS_SECRET_ACCESS_KEY="..."

# EC2 configuration
AWS_KEYPAIR_NAME="CNV-Key"
AWS_EC2_SSH_KEYPAR_PATH="/absolute/path/to/CNV-Key.pem"
AWS_SECURITY_GROUP="sg-0123456789abcdef0"

# IAM profiles
IAM_INSTANCE_PROFILE_NAME="WorkerInstanceProfile"
AWS_MASTER_IAM_PROFILE="MasterInstanceProfile"

# Application configuration
LOAD_BALANCER_PORT="8000"
```

The deployment scripts load this file automatically. It is ignored by git, so never commit it. The variable name `AWS_EC2_SSH_KEYPAR_PATH` is spelled that way on purpose, because the scripts use that exact name.

## Step 3: Java configuration (`aws.properties`)

The master node reads its own configuration file to know how to start workers. Edit `common/src/main/resources/aws.properties` and use the same values as in your `.env`:

```properties
region=us-east-1
securityGroup=sg-0123456789abcdef0
keyPairName=CNV-Key
launchTemplateName=CNV-LaunchTemplate
```

Do this **before** deploying. Rebuilding is required for changes to take effect, and the deploy scripts do that for you.

## Step 4: Deployment

Two scripts in `scripts/` do the whole job. Both compile the project, deploy the Lambda function, and optionally build the worker image (AMI) and Launch Template. When asked, answer `y` the first time or after changing worker code, and `n` to reuse what exists.

### Option A: master on your machine (testing)

The master runs locally, for example at `localhost:8000`. It still starts real EC2 workers and uses the real Lambda function.

```bash
./scripts/deploy-all-local.sh
```

### Option B: master on EC2 (full cloud deployment)

This also starts a `t3.micro` instance for the master, uploads the compiled master JAR and runs it in the background.

```bash
./scripts/deploy-all-cloud.sh
```

When it finishes, it prints the master's public DNS name. Send requests to `http://<MASTER_DNS>:<LOAD_BALANCER_PORT>/`. To follow the master's logs, SSH into the instance and run `tail -f /home/ec2-user/master.log`.

## Step 5: Teardown

> **Even with a local master, AWS resources are running** (workers, Lambda, DynamoDB tables) and cost money until you remove them.

1. If the master runs locally, stop it with `CTRL+C`.
2. Whichever option you used, remove everything:
   ```bash
   ./scripts/terminate-cloud.sh
   ```

The script removes the master and worker instances, the Lambda function and its role, the Launch Template, the DynamoDB tables and the AMI. **Deleting the tables also deletes all stored request metrics.**

It keeps your key pair, security group and IAM profiles, so you can redeploy at any time.

Because the script hides most AWS CLI errors, check the EC2, Lambda and DynamoDB consoles afterwards to confirm everything is gone. Terminated instances can stay visible for about an hour.