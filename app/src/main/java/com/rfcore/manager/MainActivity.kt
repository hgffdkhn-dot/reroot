package com.rfcore.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfcore.manager.ui.MainViewModel
import rfcore.daemon.AuditRecord
import rfcore.daemon.PolicyRecord
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
private val viewModel: MainViewModel by viewModels()

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContent {
MaterialTheme {
MainAppScaffold(viewModel)
}
}
}
}

sealed class Screen {
object Overview : Screen()
object AuditLog : Screen()
data class Edit(val record: PolicyRecord) : Screen()
}

@Composable
fun MainAppScaffold(viewModel: MainViewModel) {
var currentScreen by remember { mutableStateOf<Screen>(Screen.Overview) }
var currentTab by remember { mutableStateOf(0) }

Scaffold(
bottomBar = {
if (currentScreen !is Screen.Edit) {
NavigationBar {
NavigationBarItem(
selected = currentTab == 0,
onClick = {
currentTab = 0
currentScreen = Screen.Overview
},
icon = { Icon(Icons.Default.Home, null) },
label = { Text("Policies") }
)
NavigationBarItem(
selected = currentTab == 1,
onClick = {
currentTab = 1
currentScreen = Screen.AuditLog
},
icon = { Icon(Icons.Default.List, null) },
label = { Text("Audit") }
)
}
}
}
) { paddingValues ->
Box(modifier = Modifier.padding(paddingValues)) {
when (val screen = currentScreen) {
is Screen.Overview -> {
val policies by viewModel.filteredPolicies.collectAsState()
val query by viewModel.searchQuery.collectAsState()
PolicyOverviewScreen(
policies = policies,
searchQuery = query,
onQueryChange = { viewModel.updateSearchQuery(it) },
onToggle = { record, grant -> viewModel.toggleCapability(record, grant) },
onEditClick = { currentScreen = Screen.Edit(it) }
)
}
is Screen.AuditLog -> {
val logs by viewModel.auditLogs.collectAsState()
AuditLogScreen(
logs = logs,
onLoadMore = { viewModel.loadMoreAuditLogs() }
)
}
is Screen.Edit -> {
PolicyEditScreen(
record = screen.record,
onBack = { currentScreen = Screen.Overview },
onSave = { grant, expiry ->
viewModel.savePolicyDetail(
uid = screen.record.uid,
packageName = screen.record.packageName,
capability = screen.record.capability,
grant = grant,
expiresAt = expiry
)
currentScreen = Screen.Overview
}
)
}
}
}
}
}

@Composable
fun PolicyOverviewScreen(
policies: List<PolicyRecord>,
searchQuery: String,
onQueryChange: (String) -> Unit,
onToggle: (PolicyRecord, Boolean) -> Unit,
onEditClick: (PolicyRecord) -> Unit
) {
Column(modifier = Modifier.fillMaxSize()) {
OutlinedTextField(
value = searchQuery,
onValueChange = onQueryChange,
label = { Text("Search Package or UID") },
modifier = Modifier
.fillMaxWidth()
.padding(16.dp)
)
LazyColumn(
modifier = Modifier.fillMaxSize(),
contentPadding = PaddingValues(16.dp),
verticalArrangement = Arrangement.spacedBy(12.dp)
) {
items(policies) { policy ->
Card(
modifier = Modifier
.fillMaxWidth()
.clickable { onEditClick(policy) }
) {
Row(
modifier = Modifier
.fillMaxWidth()
.padding(16.dp),
verticalAlignment = Alignment.CenterVertically
) {
Column(modifier = Modifier.weight(1f)) {
Text(text = policy.packageName, fontWeight = FontWeight.Bold)
Text(text = "UID: ${policy.uid}", style = MaterialTheme.typography.bodySmall)
Text(text = policy.capability, style = MaterialTheme.typography.bodyMedium)
}
Switch(
checked = policy.isGranted == 1,
onCheckedChange = { onToggle(policy, it) }
)
}
}
}
}
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyEditScreen(
record: PolicyRecord,
onBack: () -> Unit,
onSave: (Boolean, Long) -> Unit
) {
var isGranted by remember { mutableStateOf(record.isGranted == 1) }
var isTemporary by remember { mutableStateOf(record.expiresAt > 0) }
var hoursCount by remember { mutableStateOf("1") }

Scaffold(
topBar = {
TopAppBar(
title = { Text("Edit Capability") },
navigationIcon = {
IconButton(onClick = onBack) {
Icon(Icons.Default.ArrowBack, null)
}
}
)
}
) { paddingValues ->
Column(
modifier = Modifier
.padding(paddingValues)
.padding(16.dp)
.fillMaxSize(),
verticalArrangement = Arrangement.spacedBy(16.dp)
) {
Text(text = "Target: ${record.packageName}", fontWeight = FontWeight.Bold)
Text(text = "UID: ${record.uid}")
Text(text = "Capability: ${record.capability}")

Row(verticalAlignment = Alignment.CenterVertically) {
Checkbox(checked = isGranted, onCheckedChange = { isGranted = it })
Text("Authorize Access")
}

Row(verticalAlignment = Alignment.CenterVertically) {
Checkbox(checked = isTemporary, onCheckedChange = { isTemporary = it })
Text("Set Temporary Expiration")
}

if (isTemporary) {
OutlinedTextField(
value = hoursCount,
onValueChange = { hoursCount = it },
label = { Text("Expire after (Hours)") },
modifier = Modifier.fillMaxWidth()
)
}

Button(
onClick = {
val expiryTime = if (isTemporary) {
val hours = hoursCount.toLongOrNull() ?: 1L
System.currentTimeMillis() / 1000 + (hours * 3600)
} else {
0L
}
onSave(isGranted, expiryTime)
},
modifier = Modifier.fillMaxWidth()
) {
Text("Save Changes")
}
}
}
}

@Composable
fun AuditLogScreen(
logs: List<AuditRecord>,
onLoadMore: () -> Unit
) {
val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

Column(modifier = Modifier.fillMaxSize()) {
LazyColumn(
modifier = Modifier
.weight(1f)
.fillMaxWidth(),
contentPadding = PaddingValues(16.dp),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
items(logs) { log ->
Card(modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(12.dp)) {
Row(modifier = Modifier.fillMaxWidth()) {
Text(text = "UID: ${log.uid}", fontWeight = FontWeight.Bold)
Spacer(modifier = Modifier.weight(1f))
Text(
text = if (log.status == 0) "SUCCESS" else "DENIED",
color = if (log.status == 0) Color(0xFF2E7D32) else Color(0xFFC62828),
fontWeight = FontWeight.Bold
)
}
Text(text = "Action: ${log.capability}", style = MaterialTheme.typography.bodyMedium)
if (log.details.isNotEmpty()) {
Text(text = "Details: ${log.details}", style = MaterialTheme.typography.bodySmall)
}
Text(
text = dateFormat.format(Date(log.timestamp * 1000)),
style = MaterialTheme.typography.labelSmall,
color = Color.Gray
)
}
}
}
}
Button(
onClick = onLoadMore,
modifier = Modifier
.fillMaxWidth()
.padding(16.dp)
) {
Text("Load More")
}
}
}}