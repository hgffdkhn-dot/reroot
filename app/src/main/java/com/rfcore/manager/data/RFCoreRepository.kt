package com.rfcore.manager.data

import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rfcore.daemon.AuditRecord
import rfcore.daemon.PolicyRecord
import rfcore.sdk.RFCoreClient

class RFCoreRepository private constructor() {
private val _policies = MutableStateFlow<List<PolicyRecord>>(emptyList())
val policies: StateFlow<List<PolicyRecord>> = _policies.asStateFlow()

private val _auditLogs = MutableStateFlow<List<AuditRecord>>(emptyList())
val auditLogs: StateFlow<List<AuditRecord>> = _auditLogs.asStateFlow()

companion object {
@Volatile
private var instance: RFCoreRepository? = null

fun getInstance(): RFCoreRepository {
return instance ?: synchronized(this) {
instance ?: RFCoreRepository().also { instance = it }
}
}
}

suspend fun fetchPolicies() = withContext(Dispatchers.IO) {
try {
val client = RFCoreClient.getInstance()
val remotePolicies = client.policies
if (remotePolicies != null) {
_policies.value = remotePolicies
}
} catch (e: RemoteException) {
_policies.value = emptyList()
} catch (e: SecurityException) {
_policies.value = emptyList()
}
}

suspend fun fetchAuditLogs(limit: Int, offset: Int, append: Boolean) = withContext(Dispatchers.IO) {
try {
val client = RFCoreClient.getInstance()
val remoteLogs = client.getAuditLogs(limit, offset)
if (remoteLogs != null) {
if (append) {
_auditLogs.value = _auditLogs.value + remoteLogs
} else {
_auditLogs.value = remoteLogs
}
}
} catch (e: RemoteException) {
if (!append) _auditLogs.value = emptyList()
} catch (e: SecurityException) {
if (!append) _auditLogs.value = emptyList()
}
}

suspend fun updateCapability(uid: Int, packageName: String, capability: String, grant: Boolean, expiresAt: Long): Boolean = withContext(Dispatchers.IO) {
try {
val client = RFCoreClient.getInstance()
val success = if (grant) {
client.grantCapability(uid, packageName, capability, 1, expiresAt)
} else {
client.revokeCapability(uid, capability)
}
if (success) {
fetchPolicies()
}
success
} catch (e: Exception) {
false
}
}
}