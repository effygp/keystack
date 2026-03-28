# Keystack

**Keystack** is an open-source AWS cloud emulator in **Kotlin** (JVM), providing a modular, permissively licensed alternative to LocalStack.

## Features

- **Protocol Engine:** Supports AWS Query and JSON protocols.
- **Service Providers:** Modular architecture for implementing AWS services.
- **State Management:** Persistent, account-region isolated state.
- **Core Services:**
  - **SQS:** Standard and FIFO queues with long polling.
  - **S3:** Bucket and object operations with filesystem storage.
  - **DynamoDB:** In-memory table and item store.
  - **SNS:** Topic and subscription management.
  - **Lambda:** Mock invocation engine for function management.
  - **IAM & STS:** Permissive identity and token services.
  - **CloudWatch:** Time-series metric storage.
  - **CloudFormation:** Template engine for infrastructure deployment.

## Getting Started

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) (Recommended)
- OR [JDK 21+](https://adoptium.net/) and [Gradle](https://gradle.org/install/) (for local development)

### Running with Docker (Recommended)

The easiest way to run Keystack is using Docker Compose. This will build the emulator and start it on port `4566`.

```bash
cd keystack
docker compose up --build
```

### Running Locally with Gradle

If you prefer to run Keystack directly on your machine:

```bash
cd keystack
./gradlew :keystack-cli:run --args="start"
```

The emulator listens on `http://localhost:4566` by default.

### Health Check

Once the emulator is running, you can verify it's healthy:

```bash
curl http://localhost:4566/_keystack/health
```

## Using with AWS CLI

You can use Keystack with any standard AWS SDK or the AWS CLI by specifying the `--endpoint-url`.

### SQS Example
```bash
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name test-queue
aws --endpoint-url=http://localhost:4566 sqs send-message --queue-url http://localhost:4566/000000000000/test-queue --message-body "Hello from Keystack"
```

### S3 Example
```bash
aws --endpoint-url=http://localhost:4566 s3 mb s3://my-bucket
aws --endpoint-url=http://localhost:4566 s3 cp myfile.txt s3://my-bucket/
```

## Configuration

Keystack can be configured via environment variables (see `docker-compose.yml` for examples).

| Variable | Default | Description |
|----------|---------|-------------|
| `NIMBUS_LOG_LEVEL` | `INFO` | Logging level (TRACE, DEBUG, INFO, WARN, ERROR) |
| `NIMBUS_PERSISTENCE` | `false` | Enable/Disable state persistence to disk |

## License

This project is licensed under the Apache License 2.0.
