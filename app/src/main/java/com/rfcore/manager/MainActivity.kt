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

// 导入你的 AIDL 接口
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
import rfcore.daemon.PolicyRecord
import rfcore.daemon.IAuthCallback // 🚨 我们新建的回调合同

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

// 连接底层的逻辑
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

// 专门用来保存底层呼叫请求的数据类
data class AuthRequestData(
    val uid: Int,
    val processName: String,
    val capability: String,
    // 这个 Future 是黑客魔法：用来挂起底层的 C++ 线程，直到用户点击按钮！
    val future: CompletableFuture<Int> 
)

@Composable
fun RFCoreMainScreen() {
    val context = LocalContext.current
    
    var rfService by remember { mutableStateOf<IRFCoreService?>(null) }
    var policyList by remember { mutableStateOf<List<PolicyRecord>>(emptyList()) }
    
    // 🚨 新增：用于触发拦截弹窗的状态
    var currentAuthRequest by remember { mutableStateOf<AuthRequestData?>(null) }

    // 🚨 核心逻辑：制造一个接听底层电话的“接线员”
    val authCallback = remember {
        object : IAuthCallback.Stub() {
            override fun onAuthRequested(uid: Int, processName: String, capability: String): Int {
                Log.w("RFCore_App", "🚨 收到底层拦截警报！UID: $uid, 进程: $processName")
                
                val future = CompletableFuture<Int>()
                
                // 将拦截信息推送到前台 UI 状态，触发弹窗
                currentAuthRequest = AuthRequestData(uid, processName, capability, future)
                
                // ⚠️ 极其硬核：在这里调用 future.get() 会直接死锁并挂起当前这个 Binder 线程！
                // 这正是我们想要的！C++ 底层会一直卡在这里等待，从而实现对目标流氓软件的“时间静止”！
                // 直到用户在 UI 上点击了按钮，调用了 future.complete()，这里才会放行，并把结果返回给 C++！
                return future.get() 
            }
        }
    }

    LaunchedEffect(Unit) {
        rfService = connectToDaemon()
        if (rfService != null) {
            try {
                // 1. 把接线员注册给底层守护进程
                rfService!!.registerAuthCallback(authCallback)
                Log.d("RFCore_App", "✅ 回调通道注册成功！正严阵以待！")
                
                // 2. 拉取列表
                policyList = rfService!!.policies?.toList() ?: emptyList() 
            } catch (e: Exception) {
                Toast.makeText(context, "初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "未连接到底层，请确认在宽容模式下！", Toast.LENGTH_LONG).show()
        }
    }

    // ----------------------------------------------------
    // 🚨 拦截弹窗 UI
    // ----------------------------------------------------
    currentAuthRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { 
                // 如果用户强行点旁边取消，默认拒绝
                request.future.complete(0)
                currentAuthRequest = null 
            },
            title = { Text("⚠️ 敏感权限请求", color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("有一个应用正在尝试越权访问！")
                    Spacer(Modifier.height(8.dp))
                    Text("UID: ${request.uid}", fontWeight = FontWeight.Bold)
                    Text("进程: ${request.processName}", fontWeight = FontWeight.Bold)
                    Text("请求动作: ${request.capability}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(onClick = {
                    // 用户点击允许，解冻底层线程并返回 1
                    request.future.complete(1)
                    currentAuthRequest = null
                }) { Text("允许执行") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    // 用户点击拒绝，解冻底层线程并返回 0
                    request.future.complete(0)
                    currentAuthRequest = null
                }) { Text("残忍拒绝") }
            }
        )
    }

    // ----------------------------------------------------
    // 主界面渲染 (和之前一样)
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
