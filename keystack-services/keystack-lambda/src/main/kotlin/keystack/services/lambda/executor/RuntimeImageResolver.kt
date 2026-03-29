package keystack.services.lambda.executor

object RuntimeImageResolver {
    private const val IMAGE_PREFIX = "public.ecr.aws/lambda/"

    private val IMAGE_MAPPING = mapOf(
        "python3.9"   to "python:3.9",
        "python3.10"  to "python:3.10",
        "python3.11"  to "python:3.11",
        "python3.12"  to "python:3.12",
        "python3.13"  to "python:3.13",
        "nodejs18.x"  to "nodejs:18",
        "nodejs20.x"  to "nodejs:20",
        "nodejs22.x"  to "nodejs:22",
        "java21"      to "java:21",
        "java17"      to "java:17",
        "dotnet8"     to "dotnet:8",
        "ruby3.3"     to "ruby:3.3",
        "provided.al2023" to "provided:al2023"
    )

    fun getImageForRuntime(runtime: String): String {
        val suffix = IMAGE_MAPPING[runtime]
            ?: throw IllegalArgumentException("Unsupported runtime: $runtime")
        return "$IMAGE_PREFIX$suffix"
    }
}
