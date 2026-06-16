package com.rfcore.manager

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// =======================================================
// 导入 AIDL 接口
import rfcore.daemon.IRFCoreBootstrap
import rfcore.daemon.IRFCoreService
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
                    RFCoreTestScreen()
                }
            }
        }
    }
}

// ==========================================
// 🚨 核心逻辑：完美适配 getWorker() 的两段式获取
// ==========================================
fun connectToDaemon(): IRFCoreService? {
    try {
        Log.d("RFCore_App", "1. 开始通过反射调用 ServiceManager...")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
        
        // 第一阶段：拿到看门人
        val rawBinder = getServiceMethod.invoke(null, "rfcore.bootstrap") as IBinder?
        
        if (rawBinder != null) {
            Log.d("RFCore_App", "2. 成功拿到原生 Binder，准备转换为看门人 Bootstrap")
            val bootstrap = IRFCoreBootstrap.Stub.asInterface(rawBinder)
            
            Log.d("RFCore_App", "3. 呼叫看门人，获取真正的 Worker...")
            
            // 🚨 核心修复：直接调用 getWorker()！
            // AIDL 已经定义了返回 IRFCoreService，不需要再次 asInterface 强转！
            val worker = bootstrap.getWorker() 
            
            Log.d("RFCore_App", "4. 完美拿到 Worker 实例！")
            return worker
        } else {
            Log.e("RFCore_App", "ServiceManager 返回了 null，请确保开启了宽容模式")
        }
    } catch (e: Exception) {
        Log.e("RFCore_App", "连接底层发生致命崩溃", e)
        throw e
    }
    return null
}

// ==========================================
// 界面：测试控制台
// ==========================================
@Composable
fun RFCoreTestScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "RFCore 终极测试控制台", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
            try {
                val service = connectToDaemon()
                if (service != null) {
                    // 1. 测试心跳 (调用底层 getStatus)
                    val status = service.status 
                    
                    // 2. 注入一条假数据 (UID: 10000, 授权读文件)
                    val isSuccess = service.grantCapability(
                        10000, 
                        "com.test.dummy", 
                        "CAP_READ_FILE", 
                        1, 
                        0L
                    )
                    
                    Toast.makeText(context, "🎉 完美打通底层!\n状态码: $status\n假数据注入: $isSuccess", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "❌ 拿不到 Service，检查是否忘记开启宽容模式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 将异常抛出至前台，不再只是白屏或 null
                Toast.makeText(context, "💥 崩溃死因:\n${e.javaClass.simpleName}\n${e.message}", Toast.LENGTH_LONG).show()
            }
        }) {
            Text("点我连接底层 并 注入测试策略")
        }
    }
}
