package keystack.services.iam

import keystack.gateway.RequestContext
import keystack.gateway.ServiceException
import keystack.provider.AwsOperation
import keystack.provider.ServiceProvider
import keystack.state.AccountRegionStore
import org.slf4j.LoggerFactory
import java.time.Instant

class IamProvider : ServiceProvider {
    override val serviceName = "iam"
    private val logger = LoggerFactory.getLogger(IamProvider::class.java)
    
    // IAM is global, so we use a fixed region for storage within an account
    private val GLOBAL_REGION = "aws-global"
    private val stores = AccountRegionStore("iam") { IamStore() }

    private fun getStore(context: RequestContext): IamStore {
        return stores[context.accountId, GLOBAL_REGION]
    }

    @AwsOperation("CreateRole")
    fun createRole(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val roleName = params["RoleName"] as? String ?: throw ServiceException("MissingParameter", "RoleName is required")
        val assumeRolePolicyDocument = params["AssumeRolePolicyDocument"] as? String ?: throw ServiceException("MissingParameter", "AssumeRolePolicyDocument is required")
        val path = params["Path"] as? String ?: "/"
        val description = params["Description"] as? String
        
        val store = getStore(context)
        if (store.roles.containsKey(roleName)) {
            throw ServiceException("EntityAlreadyExists", "Role with name $roleName already exists.")
        }
        
        val arn = "arn:aws:iam::${context.accountId}:role$path$roleName"
        val role = IamRole(
            roleName = roleName,
            arn = arn,
            path = path,
            assumeRolePolicyDocument = assumeRolePolicyDocument,
            description = description
        )
        
        store.roles[roleName] = role
        logger.info("Created IAM role: {} for account: {}", roleName, context.accountId)
        
        return mapOf("Role" to role.toMap())
    }

    @AwsOperation("GetRole")
    fun getRole(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val roleName = params["RoleName"] as? String ?: throw ServiceException("MissingParameter", "RoleName is required")
        val store = getStore(context)
        
        val role = store.roles[roleName] ?: throw ServiceException("NoSuchEntity", "The role with name $roleName cannot be found.")
        return mapOf("Role" to role.toMap())
    }

    @AwsOperation("DeleteRole")
    fun deleteRole(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val roleName = params["RoleName"] as? String ?: throw ServiceException("MissingParameter", "RoleName is required")
        val store = getStore(context)
        
        if (store.roles.remove(roleName) == null) {
            throw ServiceException("NoSuchEntity", "The role with name $roleName cannot be found.")
        }
        logger.info("Deleted IAM role: {} for account: {}", roleName, context.accountId)
        return emptyMap()
    }

    @AwsOperation("ListRoles")
    fun listRoles(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val store = getStore(context)
        val roles = store.roles.values.map { it.toMap() }
        return mapOf("Roles" to roles, "IsTruncated" to false)
    }

    @AwsOperation("CreatePolicy")
    fun createPolicy(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val policyName = params["PolicyName"] as? String ?: throw ServiceException("MissingParameter", "PolicyName is required")
        val policyDocument = params["PolicyDocument"] as? String ?: throw ServiceException("MissingParameter", "PolicyDocument is required")
        val path = params["Path"] as? String ?: "/"
        val description = params["Description"] as? String
        
        val store = getStore(context)
        val arn = "arn:aws:iam::${context.accountId}:policy$path$policyName"
        
        if (store.policies.containsKey(arn)) {
            throw ServiceException("EntityAlreadyExists", "Policy with name $policyName already exists.")
        }
        
        val policy = IamPolicy(
            policyName = policyName,
            arn = arn,
            path = path,
            document = policyDocument,
            description = description
        )
        
        store.policies[arn] = policy
        logger.info("Created IAM policy: {} for account: {}", policyName, context.accountId)
        
        return mapOf("Policy" to policy.toMap())
    }

    @AwsOperation("AttachRolePolicy")
    fun attachRolePolicy(context: RequestContext, params: Map<String, Any?>): Map<String, Any?> {
        val roleName = params["RoleName"] as? String ?: throw ServiceException("MissingParameter", "RoleName is required")
        val policyArn = params["PolicyArn"] as? String ?: throw ServiceException("MissingParameter", "PolicyArn is required")
        
        val store = getStore(context)
        val role = store.roles[roleName] ?: throw ServiceException("NoSuchEntity", "The role with name $roleName cannot be found.")
        // In permissive mode, we don't strictly need to check if the policy exists, but let's do it for better emulation
        // if (!store.policies.containsKey(policyArn)) {
        //     throw ServiceException("NoSuchEntity", "The policy with ARN $policyArn cannot be found.")
        // }
        
        role.attachedPolicies.add(policyArn)
        logger.info("Attached policy {} to role {} for account: {}", policyArn, roleName, context.accountId)
        
        return emptyMap()
    }

    private fun IamRole.toMap() = mapOf(
        "Path" to path,
        "RoleName" to roleName,
        "RoleId" to roleId,
        "Arn" to arn,
        "CreateDate" to createDate.toString(),
        "AssumeRolePolicyDocument" to assumeRolePolicyDocument,
        "Description" to description,
        "MaxSessionDuration" to maxSessionDuration
    )

    private fun IamPolicy.toMap() = mapOf(
        "PolicyName" to policyName,
        "PolicyId" to policyId,
        "Arn" to arn,
        "Path" to path,
        "DefaultVersionId" to defaultVersionId,
        "AttachmentCount" to attachmentCount,
        "PermissionsBoundaryUsageCount" to permissionsBoundaryUsageCount,
        "IsAttachable" to isAttachable,
        "Description" to description,
        "CreateDate" to createDate.toString(),
        "UpdateDate" to updateDate.toString()
    )
}
