<div align="center">

# Nature@Cloud

**An auto-scaling cloud cluster for compute-heavy scientific workloads, built on AWS EC2 and Lambda.**

![Java](https://img.shields.io/badge/Java-11%2B-orange?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-multi--module-C71A36?logo=apachemaven&logoColor=white)
![AWS](https://img.shields.io/badge/AWS-EC2%20%7C%20Lambda%20%7C%20DynamoDB-FF9900?logo=amazonaws&logoColor=white)

[Features](#features) · [Architecture](#architecture) · [Getting Started](#getting-started) · [Deployment](#deployment) · [Stress Testing](#stress-testing)

</div>

---

## Overview

Nature@Cloud runs three kinds of computational workloads: **DNA sequence matching**, **Gray-Scott reaction-diffusion simulations**, and **fractal generation**.

A master node estimates the cost of each incoming request and routes it to an EC2 worker. It adds or removes EC2 instances as load changes. When the cluster is full and new instances are still booting, it sends small requests to AWS Lambda so they do not wait.

## Features

| Feature | What it does |
|---|---|
| **Lambda spillover** | When the cluster reaches its capacity limit, small requests go to AWS Lambda instead of waiting for a new EC2 instance to boot. |
| **Adaptive routing** | Uses *Least-Loaded* routing when traffic is low, to spread requests evenly. Switches to *Best-Fit* when the queue grows, to pack requests onto fewer instances and keep room for heavy ones. |
| **Bytecode instrumentation** | Javassist injects monitoring code at runtime to measure memory allocation, which is used to estimate the cost of each request. |
| **Two-routine auto-scaler** | The *emergency routine* reacts immediately to sudden traffic changes. The *default routine* uses CloudWatch metrics to scale in only after the load has stayed low. |
| **Health management** | A health daemon polls every instance. If one crashes, it is removed from the pool and a replacement is started, even if the normal scaling limits would block it. |

## Architecture

The project is a Maven multi-module build with one module per role:

| Module | Role |
|---|---|
| `master/` | Control plane. Contains the `SmartRouter` (load balancer), `AutoScalerDaemon` and `HealthDaemon`. Keeps an atomic counter of total system demand, so routing decisions do not wait on slow queries. |
| `webserver/` | Multithreaded web server that runs on EC2 workers, launched from predefined AWS Launch Templates. |
| `faas-worker/` | AWS Lambda handler. It converts Lambda events into plain Java calls, so the same workload code runs on both EC2 and Lambda. |
| `instrumentation/` | Javassist agent that measures memory allocation at runtime. |
| `common/` | Code shared by all modules. |

**How request cost is estimated.** Request parameters are grouped into buckets and looked up in DynamoDB, which stores past execution metrics. For request types with no history, a local heuristic provides the estimate.

## Tech Stack

| Area | Technologies |
|---|---|
| Language | Java 11+ |
| AWS services | EC2, Lambda, DynamoDB, CloudWatch, IAM instance profiles |
| Libraries and tools | Apache Maven, Javassist, AWS SDK v2 |
| Deployment | Bash scripts that create, deploy and tear down all AWS resources |

## Getting Started

### Prerequisites

- Java 11 or newer
- Apache Maven
- [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) configured with credentials that can manage EC2, Lambda, DynamoDB, CloudWatch and IAM
- `jq` and `nc` (netcat), used by the deployment scripts

### Build

From the repository root, build all modules:

```bash
mvn clean package
```

## Deployment

The `scripts/` directory contains everything needed to deploy to AWS or tear it down again.

> **Cost warning:** these scripts create billable AWS resources (EC2 instances, Lambda functions, DynamoDB tables). Always run the teardown script when you finish.

1. **One-time AWS setup.** Create the IAM roles, key pair and security group by following [AWS_SETUP.md](docs/AWS_SETUP.md) and [DEPLOYMENT.md](docs/DEPLOYMENT.md). Then copy the template and fill in your values:

   ```bash
   cp .env.example .env
   ```

2. **Deploy.** The script builds the project, deploys the Lambda function, optionally builds the worker AMI and EC2 Launch Template, and starts the master node on EC2:

   ```bash
   ./scripts/deploy-all-cloud.sh
   ```

   To run the master node on your own machine instead (Lambda and workers still run on AWS), use `./scripts/deploy-all-local.sh`.

3. **Tear down.** One command removes everything the deployment created: master and worker instances, the Lambda function, the Launch Template, the DynamoDB tables and the AMI.

   ```bash
   ./scripts/terminate-cloud.sh
   ```

   This also deletes the stored request metrics.

See [scripts/README.md](scripts/README.md) for what each script does.

## Stress Testing

To simulate a large traffic spike and check that Lambda spillover works, use the scripts in `scripts/stress_tests/`. They send DNA, fractal and Gray-Scott requests in five sizes (`XS` to `XL`). To run everything:

```bash
./scripts/stress_tests/run_all.sh
```

To run a single heavy workload, for example:

```bash
./scripts/stress_tests/req-dna-XL.sh
```

Watch the master node logs while the test runs. You should see requests being redirected to Lambda once the EC2 cluster is full.

<details>
<summary><b>Repository layout</b></summary>

```
.
├── master/             # SmartRouter, AutoScalerDaemon, HealthDaemon
├── webserver/          # EC2 worker web server
├── faas-worker/        # AWS Lambda handler
├── instrumentation/    # Javassist memory-measurement agent
├── common/             # Shared code
├── scripts/
│   ├── aws/            # AMI, Launch Template, Lambda and master-node scripts
│   ├── local/          # Run the master node on your machine
│   └── stress_tests/   # Request scripts and DNA input files
├── .env.example        # Template for your AWS configuration
└── pom.xml             # Parent Maven build
```

</details>

## Authors

This project was done in collaboration with [Diogo Almada](https://github.com/almada39) and [Afonso Jacinto](https://github.com/AfonsoMendoncaJacinto).