package keystack.services.iam

import java.time.Instant
import java.util.*

data class IamRole(
    val roleId: String = "AROA" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16),
    val roleName: String,
    val arn: String,
    val path: String = "/",
    val assumeRolePolicyDocument: String,
    val createDate: Instant = Instant.now(),
    val description: String? = null,
    val maxSessionDuration: Int = 3600,
    val attachedPolicies: MutableSet<String> = mutableSetOf() // Policy ARNs
)

data class IamPolicy(
    val policyId: String = "ANPA" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16),
    val policyName: String,
    val arn: String,
    val path: String = "/",
    val defaultVersionId: String = "v1",
    val attachmentCount: Int = 0,
    val permissionsBoundaryUsageCount: Int = 0,
    val isAttachable: Boolean = true,
    val description: String? = null,
    val createDate: Instant = Instant.now(),
    val updateDate: Instant = Instant.now(),
    val document: String
)

data class IamUser(
    val userId: String = "AIDA" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16),
    val userName: String,
    val arn: String,
    val path: String = "/",
    val createDate: Instant = Instant.now(),
    val attachedPolicies: MutableSet<String> = mutableSetOf()
)
