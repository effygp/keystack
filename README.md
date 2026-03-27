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

- JDK 21+
- Gradle

### Running the Emulator

```bash
./gradlew :keystack-cli:run --args="start"
```

The emulator listens on `http://localhost:4566` by default.

### Using with AWS CLI

```bash
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name test-queue
```

## License

This project is licensed under the Apache License 2.0.
