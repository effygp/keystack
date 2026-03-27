package keystack.services.iam

import keystack.state.ServiceStore
import java.util.concurrent.ConcurrentHashMap

class IamStore : ServiceStore() {
    val roles = ConcurrentHashMap<String, IamRole>() // Name -> Role
    val policies = ConcurrentHashMap<String, IamPolicy>() // ARN -> Policy
    val users = ConcurrentHashMap<String, IamUser>() // Name -> User
}
