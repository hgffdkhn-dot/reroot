package com.rfcore.manager

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// AIDL 接口导入 (请确保与你的包名一致)
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord
import rfcore.daemon.IAuthCallback

const val CURRENT_VERSION = "v1.0.0"

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
// 🛠️ 系统环境与更新探测工具
// =======================================================
object EnvUtils {
    fun hasRoot(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        } catch (e: Exception) { false }
    }

    fun isABDevice(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.ab_update"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val isAB = reader.readLine()?.trim() == "true"
            reader.close()
            isAB
        } catch (e: Exception) { false }
    }
    
    fun fetchLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "RFCoreDaemon", "RFCoreAuthDB", "RFCore_App"))
            val log = process.inputStream.bufferedReader().readText()
            if (log.isBlank()) "暂无日志输出，守护进程可能尚未产生事件..." else log
        } catch (e: Exception) { "无法读取日志: ${e.message}" }
    }

    // 检查 GitHub 更新
    fun checkGitHubUpdate(onResult: (hasUpdate: Boolean, latestVersion: String, releaseNotes: String) -> Unit) {
        Thread {
            try {
                val url = URL("https://api.github.com/repos/hgffdkhn-dot/reroot/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    val latestVersion = json.getString("tag_name")
                    val body = json.getString("body")
                    
                    if (latestVersion != CURRENT_VERSION) {
                        onResult(true, latestVersion, body)
                    } else {
                        onResult(false, latestVersion, "")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

// =======================================================
// 🎨 主架构与路由控制
// =======================================================
@Composable
fun RFCoreApp() {
    // 简单的路由状态: "main" (主界面) 或 "flashing" (刷机页面)
    var currentRoute by remember { mutableStateOf("main") }
    var flashMode by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

    if (currentRoute == "main") {
        MainScreen(
            onStartFlashing = { mode, uri ->
                flashMode = mode
                selectedFileUri = uri
                currentRoute = "flashing"
            }
        )
    } else if (currentRoute == "flashing") {
        FlashingScreen(
            mode = flashMode,
            fileUri = selectedFileUri,
            onBack = { currentRoute = "main" }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onStartFlashing: (String, Uri?) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("主页", "授权", "日志", "设置")
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Lock, Icons.Filled.List, Icons.Filled.Settings)
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("rfcore_prefs", Context.MODE_PRIVATE)
    val autoUpdate = prefs.getBoolean("auto_update", true)

    // 自动更新检测弹窗状态
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateNotes by remember { mutableStateOf("") }

    // 启动时检测更新
    LaunchedEffect(Unit) {
        if (autoUpdate) {
            EnvUtils.checkGitHubUpdate { hasUpdate, version, notes ->
                if (hasUpdate) {
                    updateVersion = version
                    updateNotes = notes
                    showUpdateDialog = true
                }
            }
        }
    }

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
                0 -> HomeScreen(onStartFlashing)
                1 -> AuthScreen()
                2 -> LogScreen()
                3 -> SettingsScreen(prefs)
            }
        }
    }

    // 更新弹窗
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本: $updateVersion") },
            text = { Text("更新内容:\n$updateNotes") },
            confirmButton = {
                TextButton(onClick = { 
                    showUpdateDialog = false
                    Toast.makeText(context, "正在前往下载...", Toast.LENGTH_SHORT).show()
                    // TODO: 触发下载逻辑
                }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("暂不更新") }
            }
        )
    }
}

// =======================================================
// 🏠 标签一：主页 (安装逻辑与警告弹窗)
// =======================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onStartFlashing: (String, Uri?) -> Unit) {
    val context = LocalContext.current
    var showInstallSheet by remember { mutableStateOf(false) }

    val hasRoot by remember { mutableStateOf(EnvUtils.hasRoot()) }
    val isAB by remember { mutableStateOf(EnvUtils.isABDevice()) }

    // 警告弹窗状态
    var showDirectWarn by remember { mutableStateOf(false) }
    var showOtaWarn by remember { mutableStateOf(false) }

    // 文件选择器 (SAF)
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            showInstallSheet = false
            onStartFlashing("patch_file", uri)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("RFCore", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("独立级 Root 安全引擎", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("App 版本", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(CURRENT_VERSION, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        Button(onClick = { showInstallSheet = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Build, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("安装 / 更新 RFCore", style = MaterialTheme.typography.titleMedium)
        }
    }

    // 安装菜单 (BottomSheet)
    if (showInstallSheet) {
        ModalBottomSheet(onDismissRequest = { showInstallSheet = false }) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text("选择安装方式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                if (hasRoot) {
                    ListItem(
                        headlineContent = { Text("直接安装 (推荐)") },
                        supportingContent = { Text("修补并安装到当前活动槽位") },
                        leadingContent = { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showDirectWarn = true }
                    )
                    Divider()
                }

                ListItem(
                    headlineContent = { Text("选择并修补一个文件") },
                    supportingContent = { Text("手动修补 boot/init_boot 镜像文件") },
                    leadingContent = { Icon(Icons.Filled.Edit, null) },
                    modifier = Modifier.clickable { filePickerLauncher.launch("*/*") }
                )
                
                if (hasRoot && isAB) {
                    Divider()
                    ListItem(
                        headlineContent = { Text("安装到未使用的槽位 (OTA后)") },
                        supportingContent = { Text("系统更新后安装到另一个槽位防掉 Root") },
                        leadingContent = { Icon(Icons.Filled.Refresh, null) },
                        modifier = Modifier.clickable { showOtaWarn = true }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // 警告弹窗：直接安装
    if (showDirectWarn) {
        AlertDialog(
            onDismissRequest = { showDirectWarn = false },
            title = { Text("高危操作警告") },
            text = { Text("即将直接刷写您的 Boot 分区。这需要极高的底层权限。是否确认继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showDirectWarn = false
                    showInstallSheet = false
                    onStartFlashing("direct", null)
                }) { Text("确认刷写", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDirectWarn = false }) { Text("取消") } }
        )
    }

    // 警告弹窗：OTA 安装
    if (showOtaWarn) {
        AlertDialog(
            onDismissRequest = { showOtaWarn = false },
            title = { Text("OTA 槽位安装警告") },
            text = { Text("此功能仅限系统 OTA 更新完成后且尚未重启前使用！刷写完毕后，管理器工作模式将会切换至另一分区。是否确认？") },
            confirmButton = {
                TextButton(onClick = {
                    showOtaWarn = false
                    showInstallSheet = false
                    onStartFlashing("ota", null)
                }) { Text("确认执行", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showOtaWarn = false }) { Text("取消") } }
        )
    }
}

// =======================================================
// ⚙️ 标签四：设置页 (自动更新开关)
// =======================================================
@Composable
fun SettingsScreen(prefs: android.content.SharedPreferences) {
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean("auto_update", true)) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("应用设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        ListItem(
            headlineContent = { Text("开启自动更新") },
            supportingContent = { Text("每次打开软件时自动检测 GitHub 新版本") },
            trailingContent = {
                Switch(checked = autoUpdate, onCheckedChange = { 
                    autoUpdate = it
                    prefs.edit().putBoolean("auto_update", it).apply()
                })
            }
        )
        Divider()
        
        ListItem(
            headlineContent = { Text("检查更新") },
            supportingContent = { Text("当前版本: $CURRENT_VERSION") },
            modifier = Modifier.clickable { 
                // 手动检查更新的逻辑留空
            }
        )
    }
}

// =======================================================
// 💻 独立页面：刷机与修补终端界面
// =======================================================
@Composable
fun FlashingScreen(mode: String, fileUri: Uri?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var consoleLogs by remember { mutableStateOf("===================================\n* RFCore Deployment Engine *\n===================================\n") }
    var isFinished by remember { mutableStateOf(false) }

    // 模拟刷机过程 (后续可以在这里对接底层的真实 JNI 或 Shell 执行逻辑)
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val appendLog = { msg: String -> 
                withContext(Dispatchers.Main) { consoleLogs += "$msg\n" }
                delay(400) // 模拟耗时操作
            }

            appendLog("- Device Platform: arm64-v8a")
            when (mode) {
                "direct" -> {
                    appendLog("- 模式: 直接安装 (Direct Install)")
                    appendLog("- 正在定位活动 Boot 分区...")
                    appendLog("- 成功挂载 /dev/block/by-name/boot_a")
                    appendLog("- 提取原生 Boot 镜像: [100%]")
                    appendLog("- 正在注入 RFCore 守护进程...")
                    appendLog("- 打包新镜像完成.")
                    appendLog("- 正在刷入分区...")
                    delay(1000)
                    appendLog("- 刷入成功！ (All done!)")
                }
                "patch_file" -> {
                    appendLog("- 模式: 手动修补镜像")
                    appendLog("- 目标文件: ${fileUri?.lastPathSegment}")
                    appendLog("- 正在解包 Boot 镜像...")
                    appendLog("- 探测架构: 包含 Ramdisk")
                    appendLog("- 正在将 rfcore_daemon 写入 overlay.d/sbin...")
                    appendLog("- 生成补丁后的镜像文件...")
                    delay(800)
                    appendLog("- 输出文件已保存至: /Download/rfcore_patched.img")
                    appendLog("- All done!")
                }
                "ota" -> {
                    appendLog("- 模式: OTA 非活动槽位安装")
                    appendLog("- 警告: 检测到当前活动槽位为 _a")
                    appendLog("- 正在挂载非活动槽位 _b 的 Boot 分区...")
                    appendLog("- 正在注入 RFCore 核心文件...")
                    appendLog("- 强制更新 boot 标记...")
                    delay(1000)
                    appendLog("- 槽位切换准备完毕，重启后生效！")
                    appendLog("- All done!")
                }
            }
            withContext(Dispatchers.Main) { isFinished = true }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        // 伪终端顶部栏
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = isFinished) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = if (isFinished) Color.White else Color.Gray)
            }
            Text("终端日志", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        
        // 日志输出区域
        Text(
            text = consoleLogs,
            color = Color(0xFF00FF00),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        )
    }
}

// =======================================================
// 🛡️ 标签二：授权管理 (保持不变)
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
                    Divider()
                }
            }
        }
    }
}

// =======================================================
// 📜 标签三：系统日志 (完善版)
// =======================================================
@Composable
fun LogScreen() {
    var logs by remember { mutableStateOf("正在读取系统底层日志...") }
    val scope = rememberCoroutineScope()

    // 每次进入页面自动刷新
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val fetched = EnvUtils.fetchLogs()
            withContext(Dispatchers.Main) { logs = fetched }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("安全引擎日志", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { logs = "日志已清空" }) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空")
                }
                IconButton(onClick = { 
                    logs = "刷新中..."
                    scope.launch(Dispatchers.IO) {
                        val fetched = EnvUtils.fetchLogs()
                        withContext(Dispatchers.Main) { logs = fetched }
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
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
                modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()) // 完美支持滚动
            )
        }
    }
}
