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
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 导入你的 AIDL 接口
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord
import rfcore.daemon.IAuthCallback

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RFCoreMainScreen()
                }
            }
        }
    }
}

fun connectToDaemon(): IRFCoreService? {
    try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
        val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
        if (rawBinder != null) {
            val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
            return bootstrap.worker 
        }
    } catch (e: Exception) {
        Log.e("RFCore_App", "连接底层崩溃", e)
    }
    return null
}

data class AuthRequestData(
    val uid: Int,
    val processName: String,
    val capability: String,
    val future: CompletableFuture<Int> 
)

@Composable
fun RFCoreMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // 引入协程作用域
    
    var rfService by remember { mutableStateOf<IRFCoreService?>(null) }
    var policyList by remember { mutableStateOf<List<PolicyRecord>>(emptyList()) }
    var currentAuthRequest by remember { mutableStateOf<AuthRequestData?>(null) }

    val authCallback = remember {
        object : IAuthCallback.Stub() {
            override fun onAuthRequested(uid: Int, processName: String, capability: String): Int {
                Log.w("RFCore_App", "🚨 收到底层拦截警报！UID: $uid, 进程: $processName")
                val future = CompletableFuture<Int>()
                // 推送到前台触发弹窗显示
                currentAuthRequest = AuthRequestData(uid, processName, capability, future)
                // 挂起当前的 Binder 线程，等待用户决定
                return future.get() 
            }
        }
    }

    // 🚨 核心修复：切到后台线程初始化，防止 Binder 锁死 UI 线程
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val service = connectToDaemon()
            if (service != null) {
                rfService = service
                try {
                    rfService!!.registerAuthCallback(authCallback)
                    val policies = rfService!!.policies?.toList() ?: emptyList()
                    withContext(Dispatchers.Main) {
                        policyList = policies
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "通道初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ----------------------------------------------------
    // 拦截弹窗组件
    // ----------------------------------------------------
    currentAuthRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { 
                request.future.complete(0)
                currentAuthRequest = null 
            },
            title = { Text("⚠️ 越权行为拦截警报", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("检测到有未授权应用正在试图突破核心数据隔离：")
                    Spacer(Modifier.height(8.dp))
                    Text("目标 UID: ${request.uid}", fontWeight = FontWeight.Bold)
                    Text("可疑进程: ${request.processName}", fontWeight = FontWeight.Bold)
                    Text("越权动作: ${request.capability}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(onClick = {
                    request.future.complete(1) // 返回 1 表示放行
                    currentAuthRequest = null
                }) { Text("放行执行") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    request.future.complete(0) // 返回 0 表示拦截拒绝
                    currentAuthRequest = null
                }) { Text("残忍拒绝") }
            }
        )
    }

    // ----------------------------------------------------
    // 主界面布局
    // ----------------------------------------------------
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("RFCore 授权管理", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if(rfService != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
            Text(
                text = if (rfService != null) "✅ 守护进程已连接 | 拦截器运行中" else "❌ 底层未连接",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // 🚨 新增：模拟底层拦截功能测试按钮
        if (rfService != null) {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) { // 在后台线程发起请求
                        try {
                            // 呼叫底层的 getStatus 方法，在 C++ 内部触发反向拦截测试
                            val result = rfService!!.status
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "模拟测试完毕！底层收到的处理结果: $result", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "测试触发失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("🚀 点击模拟【底层越权拦截】双向测试")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        if (policyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无授权策略 (列表为空)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(policyList) { policy -> PolicyCard(policy) }
            }
        }
    }
}

@Composable
fun PolicyCard(policy: PolicyRecord) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "包名: ${policy.packageName}", fontWeight = FontWeight.Bold)
            Text(text = "目标 UID: ${policy.uid}")
            Text(text = "请求权限: ${policy.capability}", color = MaterialTheme.colorScheme.primary)
            Text(text = "状态: ${if (policy.isGranted == 1) "✅ 已允许" else "❌ 已拒绝"}", color = if (policy.isGranted == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
