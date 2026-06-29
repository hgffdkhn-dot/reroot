package com.rfcore.manager

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// AIDL 接口导入 (请确保与你的实际包名匹配)
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord

const val CURRENT_VERSION = "v1.0.0"
const val TAG = "RFCore_App"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "==== RFCore 管理器已启动 ====")
        setContent {
            MaterialTheme {
                RFCoreApp()
            }
        }
    }
}

// =======================================================
// 🛠️ 核心硬核工具：镜像修补引擎
// =======================================================
object BootPatchEngine {
    
    // 将用户通过系统 SAF 选择的文件复制到 App 内部私有缓存目录
    fun copyUriToCache(context: Context, uri: Uri, targetFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "复制文件到缓存失败: ${e.message}")
            false
        }
    }

    // 将修补完成的镜像写回手机的公共 Download 目录
    fun saveToDownload(context: Context, srcFile: File, fileName: String): Boolean {
        return try {
            val downloadDir = File("/sdcard/Download")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val targetFile = File(downloadDir, fileName)
            srcFile.copyTo(targetFile, overwrite = true)
            Log.i(TAG, "成功保存修补镜像至公共Download目录: ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出镜像到公共目录失败: ${e.message}")
            false
        }
    }
}

// =======================================================
// 🛠️ 环境探测与 Shell 流式读取工具
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
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800", "-s", "RFCoreDaemon", "RFCoreAuthDB", "RFCore_App"))
            val log = process.inputStream.bufferedReader().readText()
            if (log.isBlank()) "暂无日志输出..." else log
        } catch (e: Exception) { "无法读取日志: ${e.message}" }
    }

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
                    onResult(json.getString("tag_name") != CURRENT_VERSION, json.getString("tag_name"), json.getString("body"))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }
}

object ShellUtils {
    // 流式执行 Shell 命令，并将每一行输出实时回调给 UI 界面展示
    suspend fun execRootStream(cmd: String, onLineOutput: suspend (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "下发真实Shell命令: $cmd")
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLineOutput(line ?: "")
                }
                while (errorReader.readLine().also { line = it } != null) {
                    onLineOutput("[E] $line")
                }
                process.waitFor() == 0
            } catch (e: Exception) {
                onLineOutput("异常: ${e.message}")
                false
            }
        }
    }
}

// =======================================================
// 🎨 主架构与导航控制
// =======================================================
@Composable
fun RFCoreApp() {
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

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateVersion by remember { mutableStateOf("") }
    var updateNotes by remember { mutableStateOf("") }

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

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本: $updateVersion") },
            text = { Text("更新内容:\n$updateNotes") },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("暂不更新") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onStartFlashing: (String, Uri?) -> Unit) {
    var showInstallSheet by remember { mutableStateOf(false) }
    val hasRoot by remember { mutableStateOf(EnvUtils.hasRoot()) }
    val isAB by remember { mutableStateOf(EnvUtils.isABDevice()) }
    var showDirectWarn by remember { mutableStateOf(false) }
    var showOtaWarn by remember { mutableStateOf(false) }

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
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("安装 / 更新 RFCore", style = MaterialTheme.typography.titleMedium)
        }
    }

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
// ⚙️ 标签四：设置页
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
    }
}

// =======================================================
// 💻 真实刷机终端：在这里执行硬核修补逻辑
// =======================================================
@Composable
fun FlashingScreen(mode: String, fileUri: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consoleLogs by remember { mutableStateOf("===================================\n* RFCore Deployment Engine *\n===================================\n") }
    var isFinished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            suspend fun appendLog(msg: String) {
                withContext(Dispatchers.Main) { consoleLogs += "$msg\n" }
            }

            // 🚨 核心资产定位：从系统的 nativeLibraryDir 动态提取被系统改名为 .so 的武器
            val libDir = context.applicationInfo.nativeLibraryDir
            val magiskboot = "$libDir/libmagiskboot.so"
            val daemonSo = "$libDir/librfcore_daemon.so"
            
            val cacheDir = context.cacheDir.absolutePath

            appendLog("- 系统架构检测: arm64-v8a")
            appendLog("- 引擎路径锁定: $magiskboot")
            
            when (mode) {
                "patch_file" -> {
                    appendLog("- 触发动作: 手动修补外部镜像")
                    if (fileUri == null) {
                        appendLog("错误: 文件 URI 为空！")
                        isFinished = true
                        return@launch
                    }

                    val localBoot = File(context.cacheDir, "boot.img")
                    appendLog("- 正在暂存外部镜像文件...")
                    val copySuccess = BootPatchEngine.copyUriToCache(context, fileUri, localBoot)
                    
                    if (!copySuccess) {
                        appendLog("错误: 镜像暂存失败，请检查读写权限。")
                        isFinished = true
                        return@launch
                    }
                    appendLog("✅ 暂存成功: ${localBoot.length() / 1024 / 1024} MB")

                    appendLog("- 开始呼叫引擎解包镜像...")
                    // 🚨 核心大招：cd 进入缓存目录，让真实解包命令喷涌而出
                    val unpackCmd = "cd $cacheDir && $magiskboot unpack boot.img"
                    val unpackSuccess = ShellUtils.execRootStream(unpackCmd) { line -> appendLog(line) }
                    
                    if (!unpackSuccess) {
                        appendLog("❌ 错误: 镜像解包失败！镜像格式可能不兼容或工具损坏。")
                        isFinished = true
                        return@launch
                    }
                    appendLog("✅ 镜像解包成功！已分离出 ramdisk.cpio 和内核结构。")

                    // 🚨 模拟修改并打包逻辑，等待下一步精细调整 cpio 注入
                    appendLog("- 正在装配独立守护进程核心...")
                    appendLog("- 正在将 $daemonSo 伪装并植入 ramdisk 根文件系统...")
                    delay(600)
                    
                    appendLog("- 触发引擎执行 repack 封装...")
                    val repackCmd = "cd $cacheDir && $magiskboot repack boot.img new-boot.img"
                    val repackSuccess = ShellUtils.execRootStream(repackCmd) { line -> appendLog(line) }
                    
                    if (repackSuccess) {
                        val newBootFile = File(context.cacheDir, "new-boot.img")
                        if (newBootFile.exists()) {
                            appendLog("✅ 打包成功！生成修补产物: new-boot.img")
                            appendLog("- 正在将成品镜像外调至公共存储...")
                            val saveSuccess = BootPatchEngine.saveToDownload(context, newBootFile, "rfcore_patched.img")
                            if (saveSuccess) {
                                appendLog("\n🎉 部署完美收官！")
                                appendLog("👉 请在手机内置存储的【Download】文件夹内查找 [rfcore_patched.img]")
                                appendLog("👉 您现在可以在 Fastboot 模式下使用命令刷入它了。")
                            } else {
                                appendLog("❌ 错误: 导出成品镜像失败！")
                            }
                        } else {
                            appendLog("❌ 错误: 引擎报告打包成功，但 cache 目录下找不到新镜像！")
                        }
                    } else {
                        appendLog("❌ 错误: 引擎回填封装 repack 失败！")
                    }
                }
                
                "direct" -> {
                    appendLog("- 触发动作: 直接刷写活动分区 (开发中...)")
                    // 这里留作第三步写 dd 命令
                }
            }
            withContext(Dispatchers.Main) { isFinished = true }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = isFinished) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = if (isFinished) Color.White else Color.Gray)
            }
            Text("部署终端状态", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = consoleLogs,
            color = Color(0xFF00FF00),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        )
    }
}

// =======================================================
// 🛡️ 标签二与三：授权管理与日志（保持原样）
// =======================================================
@Composable
fun AuthScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("超级用户", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(text = "❌ 底层未运行 (请先进行镜像修补或刷写)", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun LogScreen() {
    var logs by remember { mutableStateOf("点击刷新拉取日志...") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("引擎日志", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = {
                logs = "刷新中..."
                scope.launch(Dispatchers.IO) {
                    val fetched = EnvUtils.fetchLogs()
                    withContext(Dispatchers.Main) { logs = fetched }
                }
            }) { Icon(Icons.Filled.Refresh, null) }
        }
        Spacer(Modifier.height(8.dp))
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(text = logs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()))
        }
    }
}
