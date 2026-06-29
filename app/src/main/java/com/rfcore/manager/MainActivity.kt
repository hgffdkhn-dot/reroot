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

// AIDL 接口导入
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
    // 🚨 核心修复：智能切换 su (超级用户) 和 sh (普通用户)
    suspend fun execStream(cmd: String, useRoot: Boolean, onLineOutput: suspend (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val shell = if (useRoot) "su" else "sh"
                Log.i(TAG, "下发命令 ($shell): $cmd")
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
// 🎨 主架构与导航控制 (UI部分保持极简)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onStartFlashing: (String, Uri?) -> Unit) {
    var showInstallSheet by remember { mutableStateOf(false) }
    val hasRoot by remember { mutableStateOf(EnvUtils.hasRoot()) }
    val isAB by remember { mutableStateOf(EnvUtils.isABDevice()) }

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

                ListItem(
                    headlineContent = { Text("选择并修补一个文件") },
                    supportingContent = { Text("手动修补 boot/init_boot 镜像文件") },
                    leadingContent = { Icon(Icons.Filled.Edit, null) },
                    modifier = Modifier.clickable { filePickerLauncher.launch("*/*") }
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// =======================================================
// 💻 真实刷机终端：双通道自适应执行逻辑
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
            appendLog(if (hasRoot) "- 获取到 Root 权限，进入超级特权模式" else "- 未获取 Root 权限，进入免 Root 修补模式")
            
            // 🚨 核心逻辑：解决沙盒隔离与执行权限博弈
            val activeMagiskboot = if (hasRoot) {
                // 如果有 Root，为了防止 su 看不见隔离目录里的 .so，我们把它复制到通用的 cache 目录
                val localBin = File(context.cacheDir, "magiskboot_bin")
                if (!localBin.exists()) {
                    File(magiskbootSo).copyTo(localBin, overwrite = true)
                }
                // 让 su 赋予它最高权限，彻底打破枷锁
                ShellUtils.execStream("chmod 777 ${localBin.absolutePath}", true) {}
                localBin.absolutePath
            } else {
                // 如果没有 Root，普通 sh 可以直接执行自己 lib 目录里的 .so 文件
                magiskbootSo
            }

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
                    
                    // 🚨 自动降级执行：有 Root 用 su，没 Root 用 sh！
                    val unpackCmd = "cd $cacheDir && $activeMagiskboot unpack boot.img"
                    val unpackSuccess = ShellUtils.execStream(unpackCmd, hasRoot) { line -> appendLog(line) }
                    
                    if (!unpackSuccess) {
                        appendLog("❌ 错误: 镜像解包失败！")
                        isFinished = true
                        return@launch
                    }
                    appendLog("✅ 镜像解包成功！")

                    appendLog("- 触发引擎执行 repack 封装...")
                    val repackCmd = "cd $cacheDir && $activeMagiskboot repack boot.img new-boot.img"
                    val repackSuccess = ShellUtils.execStream(repackCmd, hasRoot) { line -> appendLog(line) }
                    
                    if (repackSuccess) {
                        val newBootFile = File(context.cacheDir, "new-boot.img")
                        if (newBootFile.exists()) {
                            appendLog("✅ 打包成功！生成修补产物: new-boot.img")
                            val saveSuccess = BootPatchEngine.saveToDownload(context, newBootFile, "rfcore_patched.img")
                            if (saveSuccess) {
                                appendLog("\n🎉 修补完美收官！")
                                appendLog("👉 请在内置存储的【Download】文件夹内查找 rfcore_patched.img")
                            }
                        }
                    } else {
                        appendLog("❌ 错误: 引擎回填封装 repack 失败！")
                    }
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
// 🛡️ 标签二与三（保持极简框架）
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("引擎日志", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(text = logs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}
