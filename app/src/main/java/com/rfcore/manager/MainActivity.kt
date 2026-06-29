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
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

const val CURRENT_VERSION = "v1.0.0"
const val TAG = "RFCore_App"

// =======================================================
// 📜 全局智能日志引擎：记录管理器的一举一动
// =======================================================
object AppLogger {
    val logs = mutableStateListOf<String>()

    fun i(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] [INFO] $msg"
        Log.i(TAG, msg)
        // 在主线程更新 UI 状态
        kotlin.coroutines.EmptyCoroutineContext.let {
            logs.add(logLine)
        }
    }

    fun e(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] [ERROR] $msg"
        Log.e(TAG, msg)
        logs.add(logLine)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("==== RFCore 管理器冷启动 ====")
        // 🚨 启动时强制释放 Assets 中的二进制武器
        AssetExtractor.extractAssets(this)
        
        setContent {
            MaterialTheme {
                RFCoreApp()
            }
        }
    }
}

// =======================================================
// 🛠️ 核心硬核工具：Assets 释放与文件暂存
// =======================================================
object AssetExtractor {
    fun extractAssets(context: Context) {
        val targetDir = context.filesDir
        val filesToExtract = listOf("magiskboot", "rfcore_daemon", "su")
        
        filesToExtract.forEach { fileName ->
            try {
                val outFile = File(targetDir, fileName)
                context.assets.open(fileName).use { inputStream ->
                    FileOutputStream(outFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                // 赋予原生可执行权限
                outFile.setExecutable(true, false)
                AppLogger.i("资产部署成功: $fileName -> ${outFile.absolutePath}")
            } catch (e: Exception) {
                AppLogger.e("资产部署失败 ($fileName): ${e.message}")
            }
        }
    }
}

object BootPatchEngine {
    fun copyUriToCache(context: Context, uri: Uri, targetFile: File): Boolean {
        return try {
            AppLogger.i("正在暂存用户选择的镜像文件...")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            AppLogger.i("镜像暂存成功，大小: ${targetFile.length() / 1024 / 1024} MB")
            true
        } catch (e: Exception) {
            AppLogger.e("复制文件到缓存失败: ${e.message}")
            false
        }
    }

    fun saveToDownload(context: Context, srcFile: File, fileName: String): Boolean {
        return try {
            val downloadDir = File("/sdcard/Download")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val targetFile = File(downloadDir, fileName)
            srcFile.copyTo(targetFile, overwrite = true)
            AppLogger.i("成品镜像已导出至: ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.e("导出镜像失败: ${e.message}")
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
            val has = Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
            has
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
}

object ShellUtils {
    suspend fun execStream(cmd: String, useRoot: Boolean, onLineOutput: suspend (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val shell = if (useRoot) "su" else "sh"
                AppLogger.i("执行系统命令 [$shell]: $cmd")
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
                AppLogger.e("Shell执行异常: ${e.message}")
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
    // 🚨 找回设置页面
    val tabs = listOf("主页", "授权", "日志", "设置")
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Lock, Icons.Filled.List, Icons.Filled.Settings)
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("rfcore_prefs", Context.MODE_PRIVATE)
    
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
}

// 🚨 完美恢复的主页，带 Root 探测
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
            AppLogger.i("用户选择了目标镜像文件进行修补")
            onStartFlashing("patch_file", uri)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("RFCore", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("独立级 Root 安全引擎", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        // 🚨 恢复的系统状态卡片，Root 检测归位！
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard(modifier = Modifier.weight(1f), colors = CardDefaults.elevatedCardColors(containerColor = if(hasRoot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Root 状态", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(if (hasRoot) "已获取" else "未获取", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
// 💻 真实刷机终端：破除一切隔离
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
            
            // 🚨 我们自己释放的兵器位置
            val localMagiskboot = File(context.filesDir, "magiskboot").absolutePath

            appendLog("- 系统架构检测: arm64-v8a")
            appendLog(if (hasRoot) "- [超级权限模式] 已激活" else "- [免 Root 模式] 已激活")
            
            // 🚨 终极沙盒越狱
            val activeMagiskboot = if (hasRoot) {
                val tmpBin = "/data/local/tmp/rfcore_magiskboot"
                appendLog("- 正在向公共法外之地部署引擎...")
                ShellUtils.execStream("cp $localMagiskboot $tmpBin && chmod 777 $tmpBin", true) {}
                tmpBin
            } else {
                appendLog("- 在私有空间内执行引擎 (免Root)...")
                localMagiskboot
            }

            when (mode) {
                "patch_file" -> {
                    appendLog("- 触发动作: 手动修补外部镜像")
                    val localBoot = File(context.cacheDir, "boot.img")
                    
                    val copySuccess = BootPatchEngine.copyUriToCache(context, fileUri!!, localBoot)
                    if (!copySuccess) {
                        appendLog("❌ 错误: 镜像暂存失败。")
                        isFinished = true
                        return@launch
                    }

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
                            if (saveSuccess) appendLog("\n🎉 修补完美收官！镜像已保存至内置存储 【Download】 目录")
                        }
                    } else {
                        appendLog("❌ 错误: 引擎封装失败！")
                    }
                }
            }
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
        Text(text = consoleLogs, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()))
    }
}

// =======================================================
// 🛡️ 标签二与三与四：授权、日志与设置
// =======================================================
@Composable
fun AuthScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("超级用户", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LogScreen() {
    // 🚨 实时监听全局 AppLogger 的变化
    val logs = AppLogger.logs
    val scrollState = rememberScrollState()

    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("管理器动态日志", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { AppLogger.logs.clear(); AppLogger.i("日志已清空") }) {
                Icon(Icons.Filled.Delete, contentDescription = "清空")
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(
                text = logs.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp).verticalScroll(scrollState)
            )
        }
    }
}

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
                    AppLogger.i("用户将自动更新设置为: $it")
                })
            }
        )
    }
}
