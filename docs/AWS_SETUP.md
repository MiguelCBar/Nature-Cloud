# AWS Account and IAM Setup Guide

Before you can run the Nature@Cloud deployment scripts, you must create access keys and two IAM roles in the AWS Console. This is a one-time setup. After it, the deployment scripts create everything else.

---

## Part 1: Generating AWS access keys

The deployment scripts use these keys to authenticate with AWS from your terminal.

> Access keys belong to an IAM **user**, not to EC2 instances. Use an IAM user, not the root account. The user needs permission to manage EC2, Lambda, DynamoDB and CloudWatch, and also to create and delete IAM roles, because `deploy-lambda.sh` creates a role and `terminate-cloud.sh` deletes it. On a personal or course account, the simplest option is to attach `AdministratorAccess` to this user.

1. Log in to the AWS Management Console and search for **IAM**.
2. In the left sidebar, click **Users**, then click your username.
3. Open the **Security credentials** tab.
4. In the **Access keys** section, click **Create access key**.
5. For **Use case**, select **Command Line Interface (CLI)**.
6. Tick the confirmation box and click **Next**.
7. Optionally add a description such as `NatureCloudDeployment`, then click **Create access key**.
8. **Important:** copy the **Access key ID** and **Secret access key** now and paste them into your `.env` file. AWS never shows the secret key again.

Treat these keys like a password. Never commit them. When you finish the project, delete the access key from the same page.

---

## Part 2: Creating the worker role

This role lets the EC2 workers write telemetry to DynamoDB.

1. In IAM, click **Roles**, then **Create role**.
2. **Trusted entity type:** **AWS service**.
3. **Use case:** **EC2**, then click **Next**.
4. Search for `AmazonDynamoDBFullAccess`, tick it, and click **Next**.
5. **Role name:** `WorkerInstanceProfile`. It must match `IAM_INSTANCE_PROFILE_NAME` in your `.env` exactly.
6. Click **Create role**.

The console automatically creates an instance profile with the same name as an EC2 role. That is the name the scripts use.

---

## Part 3: Creating the master role

The master node needs permission to start workers, read CloudWatch metrics, invoke the Lambda function, and give the worker role to new instances.

### Step A: Create the role

1. Go to IAM > **Roles** > **Create role**.
2. **Trusted entity type:** **AWS service**. **Use case:** **EC2**. Click **Next**.
3. Tick these three policies:
   - `AmazonEC2FullAccess`
   - `CloudWatchReadOnlyAccess`
   - `AWSLambda_FullAccess`
4. Click **Next**.
5. **Role name:** `MasterInstanceProfile`. It must match `AWS_MASTER_IAM_PROFILE` in your `.env`.
6. Click **Create role**.

### Step B: Allow the master to pass the worker role

The master attaches the worker role to the instances it starts. That needs an inline policy.

1. Open the **Roles** list and click `MasterInstanceProfile`.
2. On the **Permissions** tab, choose **Add permissions** > **Create inline policy**.
3. Switch from the Visual editor to the **JSON** tab.
4. Replace the contents with the following, using your own 12-digit account ID:
   ```json
   {
       "Version": "2012-10-17",
       "Statement": [
           {
               "Effect": "Allow",
               "Action": "iam:PassRole",
               "Resource": "arn:aws:iam::<YOUR_ACCOUNT_ID>:role/WorkerInstanceProfile"
           }
       ]
   }
   ```
   This allows passing only the worker role. Using `"Resource": "*"` also works but lets the master pass any role in your account.
5. Click **Next**. **Policy name:** `AllowPassRoleToWorkers`.
6. Click **Create policy**.

---

**Setup complete.** Return to [DEPLOYMENT.md](DEPLOYMENT.md) to configure your environment and deploy.