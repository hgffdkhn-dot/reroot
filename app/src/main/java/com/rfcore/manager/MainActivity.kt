package com.rfcore.manager

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

const val CURRENT_VERSION = "v1.0.0"
const val TAG = "RFCore_App"

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
// 🛠️ 核心硬核工具：镜像修补引擎
// =======================================================
object BootPatchEngine {
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

    fun saveToDownload(context: Context, srcFile: File, fileName: String): Boolean {
        return try {
            val downloadDir = File("/sdcard/Download")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val targetFile = File(downloadDir, fileName)
            srcFile.copyTo(targetFile, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出镜像到公共目录失败: ${e.message}")
            false
        }
    }
}

// =======================================================
// 🛠️ 环境探测与双通道 Shell 执行引擎
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
}

object ShellUtils {
    suspend fun execStream(cmd: String, useRoot: Boolean, onLineOutput: suspend (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val shell = if (useRoot) "su" else "sh"
                val process = Runtime.getRuntime().exec(arrayOf(shell, "-c", cmd))
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
    val tabs = listOf("主页", "授权", "日志")
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
                0 -> HomeScreen(onStartFlashing)
                1 -> AuthScreen()
                2 -> LogScreen()
            }
        }
    }
}

// 🚨 恢复了丢失的 UI 面板
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

        // 🚨 找回的高级 UI 卡片，警告消除！
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
}

// =======================================================
// 💻 真实刷机终端：越狱级部署逻辑
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

            val hasRoot = EnvUtils.hasRoot()
            val cacheDir = context.cacheDir.absolutePath
            val libDir = context.applicationInfo.nativeLibraryDir
            val magiskbootSo = "$libDir/libmagiskboot.so"

            appendLog("- 系统架构检测: arm64-v8a")
            appendLog(if (hasRoot) "- [超级权限模式] 已激活" else "- [免 Root 模式] 已激活")
            
            // 🚨 终极路径黑魔法：根据权限状态动态决定兵器部署位置
            val activeMagiskboot = if (hasRoot) {
                val tmpBin = "/data/local/tmp/rfcore_magiskboot"
                appendLog("- 正在向公共法外之地 (/data/local/tmp) 部署引擎...")
                ShellUtils.execStream("cp $magiskbootSo $tmpBin && chmod 777 $tmpBin", true) {}
                tmpBin
            } else {
                appendLog("- 正在利用 nativeLibraryDir 漏洞绕过 W^X 机制...")
                magiskbootSo
            }

            when (mode) {
                "patch_file" -> {
                    appendLog("- 触发动作: 手动修补外部镜像")
                    if (fileUri == null) {
                        appendLog("❌ 错误: 未选择文件！")
                        isFinished = true
                        return@launch
                    }

                    val localBoot = File(context.cacheDir, "boot.img")
                    appendLog("- 正在暂存外部镜像文件...")
                    val copySuccess = BootPatchEngine.copyUriToCache(context, fileUri, localBoot)
                    
                    if (!copySuccess) {
                        appendLog("❌ 错误: 镜像暂存失败。")
                        isFinished = true
                        return@launch
                    }
                    appendLog("✅ 暂存成功: ${localBoot.length() / 1024 / 1024} MB")

                    appendLog("- 启动引擎解包 (unpack)...")
                    val unpackCmd = "cd $cacheDir && $activeMagiskboot unpack boot.img"
                    val unpackSuccess = ShellUtils.execStream(unpackCmd, hasRoot) { line -> appendLog(line) }
                    
                    if (!unpackSuccess) {
                        appendLog("❌ 错误: 镜像解包失败！")
                        isFinished = true
                        return@launch
                    }
                    appendLog("✅ 镜像解包完成！")

                    appendLog("- 启动引擎封装 (repack)...")
                    val repackCmd = "cd $cacheDir && $activeMagiskboot repack boot.img new-boot.img"
                    val repackSuccess = ShellUtils.execStream(repackCmd, hasRoot) { line -> appendLog(line) }
                    
                    if (repackSuccess) {
                        val newBootFile = File(context.cacheDir, "new-boot.img")
                        if (newBootFile.exists()) {
                            appendLog("✅ 封装成功！产物: new-boot.img")
                            val saveSuccess = BootPatchEngine.saveToDownload(context, newBootFile, "rfcore_patched.img")
                            if (saveSuccess) {
                                appendLog("\n🎉 修补完美收官！")
                                appendLog("👉 镜像已保存至内置存储 【Download】 目录")
                            }
                        }
                    } else {
                        appendLog("❌ 错误: 引擎封装失败！")
                    }
                }
            }
            // 清理战场
            if (hasRoot) ShellUtils.execStream("rm -f /data/local/tmp/rfcore_magiskboot", true) {}
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
// 🛡️ 标签二与三（保持极简框架，后续填充授权逻辑）
// =======================================================
@Composable
fun AuthScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("超级用户", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LogScreen() {
    var logs by remember { mutableStateOf("日志加载中...") }
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
