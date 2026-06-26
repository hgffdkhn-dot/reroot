package com.rfcore.manager

import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

// AIDL 接口导入 (请确保与你的包名一致)
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord
import rfcore.daemon.IAuthCallback

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RFCoreApp()
            }
        }
    }
}

// =======================================================
// 🛠️ 系统环境探测工具类
// =======================================================
object EnvUtils {
    fun hasRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun isABDevice(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.ab_update"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val isAB = reader.readLine()?.trim() == "true"
            reader.close()
            isAB
        } catch (e: Exception) {
            false
        }
    }
    
    fun fetchLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "RFCoreDaemon", "RFCoreAuthDB", "RFCore_App"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val log = reader.readText()
            reader.close()
            if (log.isBlank()) "暂无日志输出..." else log
        } catch (e: Exception) {
            "无法读取日志: ${e.message}"
        }
    }
}

// =======================================================
// 🎨 主架构：包含底部导航的脚手架
// =======================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RFCoreApp() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("主页", "授权", "日志")
    // 🚨 修复 1：将 Security 替换为核心库自带的 Lock 图标
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Lock, Icons.Filled.List)

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> AuthScreen()
                2 -> LogScreen()
            }
        }
    }
}

// =======================================================
// 🏠 标签一：主页 (状态卡片与安装逻辑)
// =======================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var showInstallSheet by remember { mutableStateOf(false) }

    val hasRoot by remember { mutableStateOf(EnvUtils.hasRoot()) }
    val isAB by remember { mutableStateOf(EnvUtils.isABDevice()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("RFCore", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("独立级 Root 安全引擎", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("App 版本", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("v1.0.0-dev", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            ElevatedCard(modifier = Modifier.weight(1f), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("系统环境", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(if (isAB) "A/B 分区" else "A-Only", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { showInstallSheet = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            // 🚨 修复 2：将 Build 替换为 Settings 图标
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("安装 / 更新 RFCore", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showInstallSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInstallSheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text("选择安装方式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                if (hasRoot) {
                    ListItem(
                        headlineContent = { Text("直接安装 (推荐)") },
                        supportingContent = { Text("修补并安装到当前活动槽位") },
                        // 🚨 修复 3：将 CheckCircle 替换为 Done 图标
                        leadingContent = { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { 
                            Toast.makeText(context, "执行直接安装...", Toast.LENGTH_SHORT).show()
                            showInstallSheet = false 
                        }
                    )
                    HorizontalDivider()
                }

                ListItem(
                    headlineContent = { Text("选择并修补一个文件") },
                    supportingContent = { Text("手动修补 boot/init_boot 镜像文件") },
                    leadingContent = { Icon(Icons.Filled.Edit, null) },
                    modifier = Modifier.clickable { 
                        Toast.makeText(context, "请选择 boot.img...", Toast.LENGTH_SHORT).show()
                        showInstallSheet = false 
                    }
                )
                
                if (hasRoot && isAB) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("安装到未使用的槽位 (OTA后)") },
                        supportingContent = { Text("系统更新后安装到另一个槽位防掉 Root") },
                        leadingContent = { Icon(Icons.Filled.Refresh, null) },
                        modifier = Modifier.clickable { 
                            Toast.makeText(context, "安装至非活动槽位...", Toast.LENGTH_SHORT).show()
                            showInstallSheet = false 
                        }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// =======================================================
// 🛡️ 标签二：授权管理
// =======================================================
@Composable
fun AuthScreen() {
    var rfService by remember { mutableStateOf<IRFCoreService?>(null) }
    var policyList by remember { mutableStateOf<List<PolicyRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
                val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
                if (rawBinder != null) {
                    val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
                    rfService = bootstrap.worker
                    val policies = rfService!!.policies?.toList() ?: emptyList()
                    withContext(Dispatchers.Main) { policyList = policies }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("超级用户", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (rfService != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer)
        ) {
            Text(
                text = if (rfService != null) "✅ RFCore 守护进程已连接" else "❌ 底层未运行 (请先安装)",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(16.dp))
        
        if (policyList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无授权应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(policyList) { policy ->
                    ListItem(
                        headlineContent = { Text(policy.packageName, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("UID: ${policy.uid} | ${policy.capability}") },
                        trailingContent = {
                            Switch(checked = policy.isGranted == 1, onCheckedChange = { /* TODO: 更新策略 */ })
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// =======================================================
// 📜 标签三：系统日志
// =======================================================
@Composable
fun LogScreen() {
    var logs by remember { mutableStateOf("加载中...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val fetched = EnvUtils.fetchLogs()
            withContext(Dispatchers.Main) { logs = fetched }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("核心日志", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { 
                scope.launch(Dispatchers.IO) {
                    val fetched = EnvUtils.fetchLogs()
                    withContext(Dispatchers.Main) { logs = fetched }
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        Spacer(Modifier.height(8.dp))
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = logs,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())
            )
        }
    }
}
