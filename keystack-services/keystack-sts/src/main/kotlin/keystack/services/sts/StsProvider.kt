package keystack.services.sts

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class StsProvider : ServiceProvider {
    override val serviceName = "sts"
    private val logger = LoggerFactory.getLogger(StsProvider::class.java)

    @AwsOperation("GetCallerIdentity")
    fun getCallerIdentity(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        logger.debug("STS GetCallerIdentity for account: {}", context.accountId)
        return mapOf(
            "Account" to context.accountId,
            "Arn" to "arn:aws:iam::${context.accountId}:root",
            "UserId" to context.accountId
        )
    }

    @AwsOperation("AssumeRole")
    fun assumeRole(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val roleArn = params["RoleArn"] as? String ?: throw ServiceException("MissingParameter", "RoleArn is required")
        val roleSessionName = params["RoleSessionName"] as? String ?: throw ServiceException("MissingParameter", "RoleSessionName is required")
        
        logger.info("STS AssumeRole: {} with session: {}", roleArn, roleSessionName)
        
        val expiration = Instant.now().plusSeconds(3600)
        return mapOf(
            "AssumedRoleUser" to mapOf(
                "Arn" to "$roleArn/$roleSessionName",
                "AssumedRoleId" to "AROA" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16) + ":" + roleSessionName
            ),
            "Credentials" to mapOf(
                "AccessKeyId" to "ASIA" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16),
                "SecretAccessKey" to UUID.randomUUID().toString().replace("-", ""),
                "SessionToken" to "MockSessionToken" + UUID.randomUUID().toString(),
                "Expiration" to expiration.toString()
            )
        )
    }
}
