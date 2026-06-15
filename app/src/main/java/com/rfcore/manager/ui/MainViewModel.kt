package com.rfcore.manager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfcore.manager.data.RFCoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rfcore.daemon.AuditRecord
import rfcore.daemon.PolicyRecord

class MainViewModel : ViewModel() {
private val repository = RFCoreRepository.getInstance()

private val _searchQuery = MutableStateFlow("")
val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

private var currentOffset = 0
private val pageSize = 20

val filteredPolicies: StateFlow<List<PolicyRecord>> = repository.policies
.combine(_searchQuery) { list, query ->
if (query.isBlank()) {
list
} else {
list.filter {
it.packageName.contains(query, ignoreCase = true) ||
it.uid.toString().contains(query)
}
}
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val auditLogs: StateFlow<List<AuditRecord>> = repository.auditLogs

init {
refreshAll()
}

fun refreshAll() {
viewModelScope.launch {
repository.fetchPolicies()
currentOffset = 0
repository.fetchAuditLogs(pageSize, currentOffset, false)
}
}

fun updateSearchQuery(query: String) {
_searchQuery.value = query
}

fun loadMoreAuditLogs() {
viewModelScope.launch {
currentOffset += pageSize
repository.fetchAuditLogs(pageSize, currentOffset, true)
}
}

fun toggleCapability(record: PolicyRecord, grant: Boolean) {
viewModelScope.launch {
repository.updateCapability(
uid = record.uid,
packageName = record.packageName,
capability = record.capability,
grant = grant,
expiresAt = record.expiresAt
)
}
}

fun savePolicyDetail(uid: Int, packageName: String, capability: String, grant: Boolean, expiresAt: Long) {
viewModelScope.launch {
repository.updateCapability(uid, packageName, capability, grant, expiresAt)
}
}
}