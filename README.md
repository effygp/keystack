# Keystack

**A free, open-source AWS cloud emulator built in Kotlin.**

Run AWS services locally in a single process. No cloud account needed. No paid tiers. Just `docker run` and go.

---

## Why Keystack?

Cloud emulators have become essential for local development and CI/CD, but existing solutions have moved core features behind paid tiers. Keystack is a ground-up Kotlin/JVM implementation that keeps the most critical AWS services free and open forever.

- Emulates **8 core AWS services** covering ~90% of typical development needs
- Single binary, single port (`4566`), drop-in replacement for AWS endpoints
- Works with **any AWS SDK** in any language — just point the endpoint URL to `localhost:4566`
- Coroutine-based architecture for high throughput with low resource usage
- Built-in state persistence and snapshot support

---

## Supported Services

| Service | Operations | Protocol | Highlights |
|---------|-----------|----------|------------|
| **SQS** | 16 | Query, JSON | Standard & FIFO queues, long polling, dead-letter queues, batch ops |
| **S3** | 24 | REST-XML | Buckets, objects, multipart uploads, versioning, presigned URLs |
| **DynamoDB** | 16 | JSON, CBOR | Tables, items, queries, scans, batch/transact ops, GSI/LSI |
| **SNS** | 15 | Query | Topics, subscriptions, fan-out to SQS/HTTP/Lambda, filter policies |
| **Lambda** | 16 | REST-JSON | Function CRUD, Docker-based invocation, event source mappings, layers |
| **IAM & STS** | 13 | Query | Roles, policies, users, access keys, AssumeRole, GetCallerIdentity |
| **CloudWatch** | 7 | Query | Metrics, alarms, statistics, namespaces, dimensions |
| **CloudFormation** | 10 | Query | Stack CRUD, intrinsic functions, dependency resolution, change sets |

**Total: 117 operations** across all services.

---

## Quick Start

### Docker (recommended)

```bash
docker run -d \
  --name keystack \
  -p 4566:4566 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  ghcr.io/keystack/keystack:latest
```

### Docker Compose

```yaml
# docker-compose.yml
services:
  keystack:
    image: ghcr.io/keystack/keystack:latest
    ports:
      - "127.0.0.1:4566:4566"
    environment:
      - KEYSTACK_DEBUG=false
      - KEYSTACK_PERSISTENCE=true
    volumes:
      - ./data:/var/lib/keystack
      - /var/run/docker.sock:/var/run/docker.sock
```

```bash
docker compose up -d
```

### CLI

```bash
# Download the CLI
brew install keystack/tap/keystack   # macOS/Linux

# Start the emulator
keystack start

# Check status
keystack status

# Reset all state
keystack reset
```

### From Source (Gradle)

```bash
git clone https://github.com/keystack/keystack.git
cd keystack
./gradlew :keystack-gateway:run
```

### Verify It's Running

```bash
curl http://localhost:4566/_keystack/health
# {"status":"running","services":["sqs","s3","dynamodb","sns","lambda","iam","sts","cloudwatch","cloudformation"]}
```

---

## Usage Examples

Point any AWS SDK or CLI at `http://localhost:4566`. Here are examples using the AWS CLI:

### SQS

```bash
# Create a queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
  --queue-name my-queue

# Send a message
aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://localhost:4566/000000000000/my-queue \
  --message-body "Hello from Keystack"

# Receive messages
aws --endpoint-url=http://localhost:4566 sqs receive-message \
  --queue-url http://localhost:4566/000000000000/my-queue
```

### S3

```bash
# Create a bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://my-bucket

# Upload a file
aws --endpoint-url=http://localhost:4566 s3 cp README.md s3://my-bucket/

# List objects
aws --endpoint-url=http://localhost:4566 s3 ls s3://my-bucket/

# Download a file
aws --endpoint-url=http://localhost:4566 s3 cp s3://my-bucket/README.md ./downloaded.md
```

### DynamoDB

```bash
# Create a table
aws --endpoint-url=http://localhost:4566 dynamodb create-table \
  --table-name Users \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

# Put an item
aws --endpoint-url=http://localhost:4566 dynamodb put-item \
  --table-name Users \
  --item '{"id":{"S":"user-1"},"name":{"S":"Alice"}}'

# Get an item
aws --endpoint-url=http://localhost:4566 dynamodb get-item \
  --table-name Users \
  --key '{"id":{"S":"user-1"}}'
```

### Lambda

```bash
# Create a function
aws --endpoint-url=http://localhost:4566 lambda create-function \
  --function-name hello \
  --runtime python3.12 \
  --handler index.handler \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --zip-file fileb://function.zip

# Invoke
aws --endpoint-url=http://localhost:4566 lambda invoke \
  --function-name hello \
  --payload '{"key":"value"}' \
  output.json
```

### SNS + SQS Fan-Out

```bash
# Create topic and queue
aws --endpoint-url=http://localhost:4566 sns create-topic --name my-topic
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name my-queue

# Subscribe queue to topic
aws --endpoint-url=http://localhost:4566 sns subscribe \
  --topic-arn arn:aws:sns:us-east-1:000000000000:my-topic \
  --protocol sqs \
  --notification-endpoint arn:aws:sqs:us-east-1:000000000000:my-queue

# Publish — message arrives in the queue
aws --endpoint-url=http://localhost:4566 sns publish \
  --topic-arn arn:aws:sns:us-east-1:000000000000:my-topic \
  --message "Fan-out works!"
```

### CloudFormation

```bash
# Deploy a stack
aws --endpoint-url=http://localhost:4566 cloudformation create-stack \
  --stack-name my-stack \
  --template-body file://template.yaml

# Check stack status
aws --endpoint-url=http://localhost:4566 cloudformation describe-stacks \
  --stack-name my-stack
```

---

## Configuration

All settings are configured via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYSTACK_GATEWAY_LISTEN` | `0.0.0.0:4566` | Host and port the gateway listens on |
| `KEYSTACK_PERSISTENCE` | `false` | Enable state persistence across restarts |
| `KEYSTACK_DATA_DIR` | `/var/lib/keystack` | Directory for persistent state snapshots |
| `KEYSTACK_DEBUG` | `false` | Enable debug-level logging |
| `KEYSTACK_SERVICES` | *(all)* | Comma-separated list of services to enable |
| `KEYSTACK_DEFAULT_REGION` | `us-east-1` | Default AWS region |
| `KEYSTACK_LOG_LEVEL` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

---

## Architecture

Keystack is a modular Kotlin/JVM application organized into focused Gradle modules:

```
keystack/
├── keystack-gateway      HTTP server (Ktor), handler chain, request routing
├── keystack-protocol     AWS protocol detection, parsing, and serialization
├── keystack-provider     Service provider framework, @AwsOperation dispatch
├── keystack-state        Account/region-scoped state stores, snapshots
├── keystack-services/    AWS service implementations
│   ├── keystack-sqs
│   ├── keystack-s3
│   ├── keystack-dynamodb
│   ├── keystack-sns
│   ├── keystack-lambda
│   ├── keystack-iam
│   ├── keystack-cloudwatch
│   └── keystack-cloudformation
├── keystack-cli          CLI application (start, status, reset)
└── keystack-test         Integration test suite
```

### Request Flow

```
AWS SDK Request
  → Ktor HTTP Server (port 4566)
    → Handler Chain
      → ServiceDetectionHandler    (identify service from auth header / target / host)
      → RequestParserHandler       (parse body per protocol: JSON, Query, XML, CBOR)
      → ServiceRequestRouter       (dispatch to @AwsOperation handler)
      → ResponseSerializerHandler  (serialize response per protocol)
    → HTTP Response
```

### Provider Pattern

Each service implements `ServiceProvider` and annotates operation handlers:

```kotlin
class SqsProvider : ServiceProvider {
    override val serviceName = "sqs"

    @AwsOperation("CreateQueue")
    fun createQueue(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        // Implementation
    }

    @AwsOperation("SendMessage")
    fun sendMessage(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        // Implementation
    }
}
```

The framework builds dispatch tables from annotations at startup using Kotlin reflection, so adding a new operation is just adding a method.

### State Management

Service state is organized hierarchically:

```
AccountRegionStore["account-id"]["region"] → ServiceStore instance
```

Each service gets isolated, per-account, per-region state. State is held in memory for speed and optionally persisted to disk as JSON snapshots when `KEYSTACK_PERSISTENCE=true`.

---

## Protocol Support

Keystack handles all major AWS API protocols:

| Protocol | Content Format | Used By | Detection Method |
|----------|---------------|---------|-----------------|
| **Query** | URL-encoded form, XML responses | SQS, SNS, IAM, STS, CloudFormation, CloudWatch | `Action` parameter |
| **JSON** | JSON body | SQS (modern), DynamoDB | `X-Amz-Target` header |
| **REST-JSON** | JSON body, URI params | Lambda | HTTP method + path |
| **REST-XML** | XML body, URI params | S3 | HTTP method + path |
| **Smithy RPC v2 CBOR** | CBOR body | DynamoDB (modern SDKs) | `Smithy-Protocol` header |

Service detection uses a multi-step cascade: signing name from `Authorization` header, `X-Amz-Target` prefix, hostname-based routing, `Action` query parameter, and path heuristics.

---

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin (JVM) | 2.1 |
| Runtime | Eclipse Temurin | JDK 21 |
| HTTP Server | Ktor (CIO engine) | 3.1.1 |
| Dependency Injection | Koin | 4.0.3 |
| Serialization | kotlinx.serialization + Jackson | 1.8.0 / 2.18.3 |
| XML | Jackson XML | 2.18.3 |
| CBOR | Jackson CBOR | 2.18.3 |
| Coroutines | kotlinx.coroutines | 1.10.1 |
| Docker SDK | docker-java | 3.4.1 |
| CLI | Clikt | 5.0.3 |
| Logging | SLF4J + Logback | 2.0.16 / 1.5.16 |
| Testing | JUnit 5 + Kotest + Testcontainers | 6.0.0 / 1.20.6 |

---

## Persistence

By default, Keystack runs fully in-memory. All state is lost on restart.

To enable persistence:

```bash
# Via environment variable
KEYSTACK_PERSISTENCE=true keystack start

# Or with Docker
docker run -d -p 4566:4566 \
  -e KEYSTACK_PERSISTENCE=true \
  -v ./data:/var/lib/keystack \
  ghcr.io/keystack/keystack:latest
```

State is saved as JSON snapshots to `KEYSTACK_DATA_DIR` on shutdown and restored on startup. You can also manage state manually:

```bash
# Reset all state (running instance)
curl -X POST http://localhost:4566/_keystack/state/reset

# Or via CLI
keystack reset
```

---

## SDK Configuration Examples

### Python (boto3)

```python
import boto3

s3 = boto3.client("s3", endpoint_url="http://localhost:4566")
s3.create_bucket(Bucket="my-bucket")
```

### JavaScript (AWS SDK v3)

```javascript
import { S3Client, CreateBucketCommand } from "@aws-sdk/client-s3";

const client = new S3Client({ endpoint: "http://localhost:4566", forcePathStyle: true });
await client.send(new CreateBucketCommand({ Bucket: "my-bucket" }));
```

### Kotlin (AWS SDK for Kotlin)

```kotlin
val client = S3Client {
    endpointUrl = Url.parse("http://localhost:4566")
    region = "us-east-1"
}
client.createBucket { bucket = "my-bucket" }
```

### Go (AWS SDK v2)

```go
cfg, _ := config.LoadDefaultConfig(context.TODO(),
    config.WithBaseEndpoint("http://localhost:4566"),
)
client := s3.NewFromConfig(cfg, func(o *s3.Options) {
    o.UsePathStyle = true
})
```

### Java (AWS SDK v2)

```java
S3Client s3 = S3Client.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .forcePathStyle(true)
    .build();
s3.createBucket(b -> b.bucket("my-bucket"));
```

---

## Contributing

Keystack is designed to make adding new services straightforward.

### Adding a New Service

1. **Create a module** under `keystack-services/` (e.g., `keystack-kinesis`)
2. **Add a service model** JSON file to `keystack-protocol/src/main/resources/models/`
3. **Implement a `ServiceProvider`** with `@AwsOperation` annotated handler methods
4. **Create a `ServiceStore`** extending the base class for state management
5. **Register the module** in `settings.gradle.kts`
6. **Write integration tests** using the AWS SDK against a running instance

See any existing service (SQS is the simplest) as a reference implementation.

### Development Setup

```bash
# Clone
git clone https://github.com/keystack/keystack.git
cd keystack

# Build
./gradlew build

# Run locally
./gradlew :keystack-gateway:run

# Run tests
./gradlew test
```

### Code Style

- Kotlin coding conventions enforced via ktlint
- Coroutines for all async operations
- `ConcurrentHashMap` for thread-safe state
- Data classes for all models

---

## Comparison with Alternatives

| Feature | Keystack | LocalStack (Free) | LocalStack (Pro) |
|---------|----------|-------------------|-----------------|
| Core services (S3, SQS, DynamoDB, SNS) | Yes | Yes | Yes |
| Lambda (Docker execution) | Yes | Yes | Yes |
| CloudFormation | Yes | Yes | Yes |
| IAM & STS | Yes | Yes | Yes |
| CloudWatch | Yes | Yes | Yes |
| State persistence | Yes | Limited | Yes |
| Multi-account support | Yes | No | Yes |
| License | Apache 2.0 | Apache 2.0 | Proprietary |
| Runtime | JVM (Kotlin) | Python | Python |
| Paid tiers | None | N/A | Required for advanced features |

---

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.

---

## Links

- [GitHub Repository](https://github.com/keystack/keystack)
- [Issue Tracker](https://github.com/keystack/keystack/issues)
- [Docker Hub](https://ghcr.io/keystack/keystack)
