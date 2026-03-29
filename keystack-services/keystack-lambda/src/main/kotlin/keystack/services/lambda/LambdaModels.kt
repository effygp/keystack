package keystack.services.lambda

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Runtime status of an individual execution environment */
enum class RuntimeStatus {
    INACTIVE, STARTING, READY, INVOKING, STARTUP_FAILED, STARTUP_TIMED_OUT, STOPPED, TIMING_OUT
}

/** How the execution environment was initialized */
enum class InitializationType(val value: String) {
    ON_DEMAND("on-demand"),
    PROVISIONED_CONCURRENCY("provisioned-concurrency")
}

/** Current state of a Lambda function version */
enum class FunctionState {
    PENDING, ACTIVE, INACTIVE, FAILED
}

/** Represents a function's deployed code */
data class FunctionCode(
    val zipFilePath: Path?,          // Path to the uploaded ZIP on disk
    val s3Bucket: String? = null,
    val s3Key: String? = null,
    val codeSha256: String = "",
    val codeSize: Long = 0,
    val isHotReloading: Boolean = false,
    val hotReloadPath: String? = null // Host path for bind-mount in dev mode
) {
    fun getUnzippedCodeLocation(): Path {
        return zipFilePath?.resolveSibling("code")
            ?: throw IllegalStateException("No code path available")
    }
}

/** Full function configuration — replaces the old minimal FunctionConfiguration */
data class FunctionConfiguration(
    val functionName: String,
    val functionArn: String,
    val runtime: String,
    val handler: String,
    val role: String,
    val description: String? = null,
    val timeout: Int = 3,
    val memorySize: Int = 128,
    val lastModified: String = Instant.now().toString(),
    val codeSize: Long = 0,
    val environment: Map<String, String>? = null,
    val code: FunctionCode? = null,
    val architecture: String = "x86_64",
    val state: FunctionState = FunctionState.PENDING,
    val packageType: String = "Zip",     // "Zip" or "Image"
    val layers: List<String> = emptyList(),
    val version: String = "\$LATEST",
    val revisionId: String = java.util.UUID.randomUUID().toString()
)

/** Result of a Lambda invocation */
data class InvocationResult(
    val requestId: String,
    val payload: ByteArray?,
    val isError: Boolean,
    val logs: String? = null,
    val executedVersion: String? = null
)

/** An invocation request */
data class Invocation(
    val payload: ByteArray,
    val invokedArn: String,
    val invocationType: String = "RequestResponse", // RequestResponse | Event | DryRun
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val clientContext: String? = null
)
