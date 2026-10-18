# Scripts

Reference for the deployment, teardown and stress-test scripts. For first-time setup (AWS account, IAM roles, `.env`, `aws.properties`), follow [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md) first.

> **Cost warning:** these scripts create billable AWS resources. Run `./scripts/terminate-cloud.sh` at the end of every work session.

## Quick start

| Goal | Command |
|---|---|
| Deploy everything on AWS, master node included | `./scripts/deploy-all-cloud.sh` |
| Deploy Lambda and workers on AWS, run the master on your machine | `./scripts/deploy-all-local.sh` |
| Send test traffic | `./scripts/stress_tests/run_all.sh` |
| Remove everything the deployment created | `./scripts/terminate-cloud.sh` |

Both `deploy-all-*` scripts run the same four steps and differ only in the last one:

1. Build the Maven project (`mvn clean install -DskipTests`).
2. Deploy or update the AWS Lambda function.
3. Optionally build the worker AMI and the EC2 Launch Template. The scripts ask `y/N` for each. Answer `y` the first time and after any change to the worker code.
4. Start the master node, on a new EC2 instance (`deploy-all-cloud.sh`) or on your machine (`deploy-all-local.sh`).

Because of the prompts, run them from an interactive terminal.

## Top-level scripts (`scripts/`)

| Script | Purpose |
|---|---|
| `deploy-all-cloud.sh` | Full pipeline, master node on EC2 |
| `deploy-all-local.sh` | Same pipeline, master node on your machine |
| `terminate-cloud.sh` | Full teardown (see [Teardown](#teardown)) |
| `stress_buckets_tmux.sh` | Opens five `tmux` panes that send mixed fractal, DNA and Gray-Scott requests in a loop. Requires `tmux`. |
| `test_worker_metrics.sh` | Smoke test: checks `/health`, sends one request per workload and prints the latest lines of the metrics file. Takes the base URL as an argument (default `http://localhost:8000`). |

## AWS scripts (`scripts/aws/`)

All of them load the project's `.env` through `config.sh`.

| Script | Purpose |
|---|---|
| `config.sh` | Loads the `.env` file and shared variables |
| `deploy-lambda.sh` | Creates the `NatureLambdaExecutionRole` IAM role if it is missing, then creates or updates the `NatureLambda` function from the `faas-worker` JAR |
| `create-image.sh` | Builds the worker AMI by running `launch-vm.sh`, `install-vm.sh` and `test-vm.sh` in order, then creates the image and terminates the temporary VM. Also creates the DynamoDB tables. Saves the AMI ID to `image.id`. |
| `launch-vm.sh` | Starts a temporary EC2 instance to be turned into the image |
| `install-vm.sh` | Installs Java 11 on that instance, copies the web server and Javassist JARs, and sets the server to start on boot |
| `test-vm.sh` | Reboots the instance and checks that the server starts by itself |
| `launch-deployment-template.sh` | Creates the Launch Template `CNV-LaunchTemplate` from the AMI in `image.id`. The master uses it to start workers. |
| `deploy-master-cloud.sh` | Starts the master on a new `t3.micro` instance, installs Java 11, uploads the master JAR and `.env`, and runs it in the background. Prints the master's URL and how to read its logs. |
| `terminate-deployment-template.sh` | Deletes the Launch Template and both DynamoDB tables |
| `deregister-image.sh` | Deletes the worker AMI and its snapshots |

## Stress tests (`scripts/stress_tests/`)

Request scripts are named `req-<workload>-<size>.sh`. The workload is `dna`, `fractals` or `grayscott`, and the size is `XS`, `S`, `M`, `L` or `XL`. `run_all.sh` runs all of them. The `.fasta` files are the inputs for the DNA workload.

## Teardown

`./scripts/terminate-cloud.sh` removes, in this order:

1. the master EC2 instance (read from `master-instance.id`)
2. all worker instances started from `CNV-LaunchTemplate`
3. the `NatureLambda` function and its `NatureLambdaExecutionRole` role
4. the Launch Template and the DynamoDB tables `NatureRequestLog` and `NatureMetricsAgg`
5. the worker AMI (read from `image.id`)

> **All stored request metrics are deleted** with the DynamoDB tables. The cost estimator starts from scratch on the next deployment, and you need to rebuild the AMI.

It keeps your key pair, security group and IAM instance profiles, so redeploying is quick.

The script hides most errors from the AWS CLI, so it can print "TEARDOWN COMPLETE" even if a step failed. Check the EC2, Lambda and DynamoDB consoles afterwards. Terminated instances can stay visible for about an hour.

## Notes

- `deploy-master-cloud.sh` copies your `.env`, including AWS access keys, to the master instance. The master already has an IAM instance profile (`AWS_MASTER_IAM_PROFILE`). Check whether it can work with the role alone, and if so, leave the keys out of the copied file.
- `image.id`, `master-instance.id` and the `*.dns` files are written by the scripts at runtime and are ignored by git.
- The Lambda function uses the `java11` runtime, which AWS is phasing out. If deployment is refused, change `--runtime` in `deploy-lambda.sh` to a supported Java runtime. See the [Lambda runtimes page](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtimes.html) for current dates.