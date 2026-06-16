package com.rfcore.manager

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// =======================================================
// 导入你的 AIDL 接口数据模型
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord
// =======================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), 
                    color = MaterialTheme.colorScheme.background
                ) {
                    RFCoreMainScreen()
                }
            }
        }
    }
}

// ==========================================
// 🚨 核心逻辑：获取底层 Worker
// ==========================================
fun connectToDaemon(): IRFCoreService? {
    try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
        
        // 1. 拿到看门人
        val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
        
        if (rawBinder != null) {
            val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
            // 2. 拿到干活的 Worker
            return bootstrap.worker 
        }
    } catch (e: Exception) {
        Log.e("RFCore_App", "连接底层崩溃", e)
    }
    return null
}

// ==========================================
// 界面：动态渲染策略列表
// ==========================================
@Composable
fun RFCoreMainScreen() {
    val context = LocalContext.current
    
    // 状态管理：保存底层服务句柄和策略列表
    var rfService by remember { mutableStateOf<IRFCoreService?>(null) }
    var policyList by remember { mutableStateOf<List<PolicyRecord>>(emptyList()) }

    // 界面一启动，自动去底层建立连接并拉取数据
    LaunchedEffect(Unit) {
        rfService = connectToDaemon()
        if (rfService != null) {
            try {
                // 🚨 核心修复：加上 .toList()，将 AIDL 的 Array 顺滑转换为 Compose 需要的 List
                policyList = rfService!!.policies?.toList() ?: emptyList() 
            } catch (e: Exception) {
                Toast.makeText(context, "拉取列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "未连接到底层，请确认在宽容模式下！", Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("RFCore 授权管理", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        // 顶部状态栏
        Card(
            modifier = Modifier.fillMaxWidth(), 
            colors = CardDefaults.cardColors(
                containerColor = if(rfService != null) MaterialTheme.colorScheme.primaryContainer 
                                 else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = if (rfService != null) "✅ 底层守护进程已连接" else "❌ 底层未连接",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 渲染策略列表
        if (policyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无授权策略 (列表为空)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(policyList) { policy ->
                    PolicyCard(policy)
                }
            }
        }
    }
}

// ==========================================
// 组件：单个策略的卡片 UI
// ==========================================
@Composable
fun PolicyCard(policy: PolicyRecord) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), 
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "包名: ${policy.packageName}", fontWeight = FontWeight.Bold)
            Text(text = "目标 UID: ${policy.uid}")
            Text(text = "请求权限: ${policy.capability}", color = MaterialTheme.colorScheme.primary)
            Text(
                text = "状态: ${if (policy.isGranted == 1) "✅ 已允许" else "❌ 已拒绝"}", 
                color = if (policy.isGranted == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
